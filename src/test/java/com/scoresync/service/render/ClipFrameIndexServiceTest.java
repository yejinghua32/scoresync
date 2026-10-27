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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipFrameIndexServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private ClipProjectRepository projects;
    private ClipFrameIndexService service;
    private Path videoFile;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("clip-index-db", ".db");
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

        AppProperties properties = new AppProperties();
        properties.getClip().setCacheDir(tempDir.resolve("cache").toString());

        videoFile = tempDir.resolve("source.mp4");
        Files.writeString(videoFile, "fake-video");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                        + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, ?, 'source.mp4', ?, ?, "
                        + "'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')",
                videoFile.toString(), Files.size(videoFile),
                Files.getLastModifiedTime(videoFile).toMillis());

        service = new ClipFrameIndexService(projects, new ClipSourceValidator(assets),
                new FakeIndexBuilder(), properties);
    }

    @Test
    void buildsIndexAndStoresFrameCount() throws Exception {
        long id = createProject();
        waitForBuild(id);

        ClipProject project = projects.findById(id);
        assertThat(project.frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.READY);
        assertThat(project.totalFrames()).isEqualTo(4);
    }

    @Test
    void indexMapsFramesToExactTimestamps() throws Exception {
        long id = createProject();
        waitForBuild(id);

        ClipFrameIndex index = service.requireIndex(projects.findById(id));

        assertThat(index.frameCount()).isEqualTo(4);
        assertThat(index.ptsUs(0)).isEqualTo(0L);
        assertThat(index.ptsUs(1)).isEqualTo(40_000L);
        assertThat(index.ptsUs(2)).isEqualTo(90_000L);
        assertThat(index.ptsUs(3)).isEqualTo(160_000L);
    }

    @Test
    void durationUsesNextFrameWhenFrameDurationUnknown() throws Exception {
        long id = createProject();
        waitForBuild(id);

        ClipFrameIndex index = service.requireIndex(projects.findById(id));

        assertThat(index.durationUs(0)).isEqualTo(40_000L);
        assertThat(index.durationUs(2)).isEqualTo(70_000L);
        assertThat(index.durationUs(3)).isEqualTo(40_000L);
    }

    @Test
    void indexIsCachedOnDiskAndReused() throws Exception {
        long id = createProject();
        waitForBuild(id);

        ClipFrameIndex first = service.requireIndex(projects.findById(id));
        ClipFrameIndex second = service.requireIndex(projects.findById(id));

        assertThat(second.frameCount()).isEqualTo(first.frameCount());
        assertThat(second.ptsUs(3)).isEqualTo(first.ptsUs(3));
    }

    @Test
    void changedSourceFileBlocksIndexUsage() throws Exception {
        long id = createProject();
        waitForBuild(id);

        Files.writeString(videoFile, "changed-content-that-has-different-size");

        assertThatThrownBy(() -> service.requireIndex(projects.findById(id)))
                .isInstanceOf(ClipSourceChangedException.class)
                .hasMessageContaining("源视频已发生变化");
    }

    @Test
    void requireIndexFailsWhenNotReady() throws IOException {
        long id = createProject();

        assertThatThrownBy(() -> service.requireIndex(projects.findById(id)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("帧索引尚未就绪");
    }

    @Test
    void failedBuildMarksProjectFailed() throws Exception {
        long id = createProject();
        service = new ClipFrameIndexService(projects,
                new ClipSourceValidator(new VideoAssetRepository(jdbc)),
                new FailingIndexBuilder(), properties());
        service.requestBuild(projects.findById(id));
        waitForStatus(id, ClipFrameIndexStatus.FAILED);

        assertThat(projects.findById(id).frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.FAILED);
        assertThat(projects.findById(id).totalFrames()).isNull();
    }

    private AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getClip().setCacheDir(tempDir.resolve("cache").toString());
        return properties;
    }

    private long createProject() throws IOException {
        long id = projects.insert("第一集", 1L, 10, 5,
                Files.size(videoFile), Files.getLastModifiedTime(videoFile).toMillis());
        return id;
    }

    private void waitForBuild(long id) throws InterruptedException {
        service.requestBuild(projects.findById(id));
        waitForStatus(id, ClipFrameIndexStatus.READY);
    }

    private void waitForStatus(long id, ClipFrameIndexStatus expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (projects.findById(id).frameIndexStatus() == expected) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(projects.findById(id).frameIndexStatus()).isEqualTo(expected);
    }

    /**
     * 假索引构建器：返回固定的四帧时间戳，含可变帧率
     */
    static class FakeIndexBuilder extends ClipFrameIndexBuilder {
        final AtomicInteger calls = new AtomicInteger();

        FakeIndexBuilder() {
            super(new AppProperties());
        }

        @Override
        public ClipFrameIndex build(Path source) {
            calls.incrementAndGet();
            return new ClipFrameIndex(
                    new long[]{0L, 40_000L, 90_000L, 160_000L},
                    new long[]{0L, 0L, 0L, 40_000L},
                    0L);
        }
    }

    /**
     * 假索引构建器：始终失败
     */
    static class FailingIndexBuilder extends ClipFrameIndexBuilder {
        FailingIndexBuilder() {
            super(new AppProperties());
        }

        @Override
        public ClipFrameIndex build(Path source) {
            throw new IllegalStateException("索引建立失败");
        }
    }
}
