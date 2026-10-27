package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.domain.ClipFrameIndexStatus;
import com.scoresync.domain.ClipProject;
import com.scoresync.repository.ClipProjectRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.service.ClipSourceValidator;
import com.scoresync.support.ClipSourceChangedException;
import com.scoresync.support.DomainValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipThumbnailServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private ClipProjectRepository projects;
    private ClipFrameIndexService indexService;
    private FakeExtractor extractor;
    private ClipThumbnailService service;
    private Path videoFile;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("clip-thumb-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE video_asset (
                  id INTEGER PRIMARY KEY, source_id INTEGER NULL,
                  absolute_path TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL,
                  file_size INTEGER NOT NULL, modified_at INTEGER NOT NULL,
                  origin_type TEXT NOT NULL CHECK (origin_type IN ('SCANNED','PICKED')),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE clip_project (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                  video_asset_id INTEGER NOT NULL REFERENCES video_asset(id),
                  large_step INTEGER NOT NULL DEFAULT 10 CHECK (large_step > 0),
                  small_step INTEGER NOT NULL DEFAULT 5 CHECK (small_step > 0),
                  source_file_size INTEGER NOT NULL, source_modified_at INTEGER NOT NULL,
                  total_frames INTEGER NULL,
                  frame_index_status TEXT NOT NULL DEFAULT 'PENDING'
                    CHECK (frame_index_status IN ('PENDING','BUILDING','READY','FAILED')),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");

        projects = new ClipProjectRepository(jdbc);
        VideoAssetRepository assets = new VideoAssetRepository(jdbc);

        videoFile = tempDir.resolve("source.mp4");
        Files.writeString(videoFile, "fake-video");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                        + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, ?, 'source.mp4', ?, ?, "
                        + "'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')",
                videoFile.toString(), Files.size(videoFile),
                Files.getLastModifiedTime(videoFile).toMillis());

        AppProperties properties = new AppProperties();
        properties.getClip().setCacheDir(tempDir.resolve("cache").toString());
        properties.getClip().setMaxCachedThumbnails(3);

        ClipFrameIndexService indexService = new ClipFrameIndexService(projects,
                new ClipSourceValidator(assets), new FakeIndexBuilder(), properties);
        this.indexService = indexService;
        extractor = new FakeExtractor();
        service = new ClipThumbnailService(indexService, new ClipSourceValidator(assets),
                extractor, properties);
    }

    @Test
    void returnsThumbnailForValidFrame() throws Exception {
        long id = readyProject();

        byte[] png = service.thumbnail(projects.findById(id), 2);

        assertThat(png).isNotEmpty();
        assertThat(new String(png, java.nio.charset.StandardCharsets.UTF_8)).contains("frame-2");
    }

    @Test
    void seeksToExactFrameTimestamp() throws Exception {
        long id = readyProject();

        service.thumbnail(projects.findById(id), 0);

        assertThat(extractor.seekSeconds).isCloseTo(0.0d, org.assertj.core.data.Offset.offset(0.0001d));
    }

    @Test
    void rejectsFrameOutOfRange() throws Exception {
        long id = readyProject();

        assertThatThrownBy(() -> service.thumbnail(projects.findById(id), 4))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("帧序号超出范围");
        assertThatThrownBy(() -> service.thumbnail(projects.findById(id), -1))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void cachesThumbnailPerFrame() throws Exception {
        long id = readyProject();

        service.thumbnail(projects.findById(id), 1);
        service.thumbnail(projects.findById(id), 1);

        assertThat(extractor.calls.get()).isEqualTo(1);
    }

    @Test
    void changedSourceFileBlocksThumbnail() throws Exception {
        long id = readyProject();
        Files.writeString(videoFile, "changed-content-with-different-size");

        assertThatThrownBy(() -> service.thumbnail(projects.findById(id), 0))
                .isInstanceOf(ClipSourceChangedException.class);
    }

    @Test
    void prunesThumbnailCacheBeyondLimit() throws Exception {
        long id = readyProject();

        for (int frame = 0; frame < 4; frame++) {
            service.thumbnail(projects.findById(id), frame);
        }

        Path thumbDir = Path.of(tempDir.resolve("cache").toString(), "thumbs",
                projects.findById(id).videoAssetId() + "-" + Files.size(videoFile) + "-"
                        + Files.getLastModifiedTime(videoFile).toMillis());
        try (var files = Files.list(thumbDir)) {
            assertThat(files.count()).isLessThanOrEqualTo(3);
        }
    }

    @Test
    void thumbnailUsesFrameTimestampFromIndex() throws Exception {
        long id = readyProject();

        service.thumbnail(projects.findById(id), 2);

        // 索引中第三帧的时间戳为 90ms
        assertThat(extractor.seekSeconds).isCloseTo(0.09d, org.assertj.core.data.Offset.offset(0.0001d));
    }

    /**
     * 创建索引就绪的项目
     * 走真实的索引建立流程，确保缓存文件与状态一致
     */
    private long readyProject() throws Exception {
        long id = projects.insert("第一集", 1L, 10, 5,
                Files.size(videoFile), Files.getLastModifiedTime(videoFile).toMillis());
        indexService.requestBuild(projects.findById(id));
        for (int i = 0; i < 200; i++) {
            if (projects.findById(id).frameIndexStatus() == ClipFrameIndexStatus.READY) {
                return id;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("帧索引未在预期时间内建立完成");
    }

    /**
     * 假索引构建器：返回固定四帧
     */
    static class FakeIndexBuilder extends ClipFrameIndexBuilder {
        FakeIndexBuilder() {
            super(new AppProperties());
        }

        @Override
        public ClipFrameIndex build(Path source) {
            return new ClipFrameIndex(
                    new long[]{0L, 40_000L, 90_000L, 160_000L},
                    new long[]{0L, 0L, 0L, 40_000L},
                    0L);
        }
    }

    /**
     * 假抽帧器：记录调用并写出可识别的占位内容
     */
    static class FakeExtractor extends ClipFrameExtractor {
        final AtomicInteger calls = new AtomicInteger();
        volatile double seekSeconds;

        FakeExtractor() {
            super(new AppProperties());
        }

        @Override
        public void extract(Path source, double seekSeconds, Path output) {
            calls.incrementAndGet();
            this.seekSeconds = seekSeconds;
            try {
                Files.createDirectories(output.getParent());
                Files.writeString(output, "fake-png-frame-" + output.getFileName());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
