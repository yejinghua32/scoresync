package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.domain.ClipFrameIndexStatus;
import com.scoresync.domain.ClipProject;
import com.scoresync.domain.ClipThumbnailFormat;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private AppProperties properties;
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

        properties = new AppProperties();
        properties.getClip().setCacheDir(tempDir.resolve("cache").toString());

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

        byte[] thumbnail = service.thumbnail(projects.findById(id), 2);

        assertThat(thumbnail).isNotEmpty();
        assertThat(new String(thumbnail, StandardCharsets.UTF_8)).contains("frame-2");
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
    void storesThumbnailInVersionedCacheDirectory() throws Exception {
        long id = readyProject();

        service.thumbnail(projects.findById(id), 2);

        Path thumbDir = thumbDir();
        assertThat(thumbDir.resolve("2.jpg")).exists();
    }

    @Test
    void reusesDiskCacheAfterRestart() throws Exception {
        long id = readyProject();
        service.thumbnail(projects.findById(id), 1);

        // 重新打开项目时新建服务实例，仍命中磁盘缓存，不重复抽帧
        ClipThumbnailService reopened = new ClipThumbnailService(indexService,
                new ClipSourceValidator(new VideoAssetRepository(jdbc)), extractor, properties);
        reopened.thumbnail(projects.findById(id), 1);

        assertThat(extractor.calls.get()).isEqualTo(1);
    }

    @Test
    void removesLegacyCacheDirectories() throws Exception {
        long id = readyProject();
        Path legacy = tempDir.resolve("cache").resolve("thumbs").resolve("1-old-1-1");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("0.png"), "old");

        service.thumbnail(projects.findById(id), 0);

        assertThat(legacy).doesNotExist();
    }

    @Test
    void prunesThumbnailCacheToGlobalByteBudget() throws Exception {
        long id = readyProject();
        extractor.contentBytes = 40;
        AppProperties limited = new AppProperties();
        limited.getClip().setCacheDir(tempDir.resolve("cache").toString());
        limited.getClip().setMaxCachedThumbnailBytes(100);
        ClipThumbnailService limitedService = new ClipThumbnailService(indexService,
                new ClipSourceValidator(new VideoAssetRepository(jdbc)), extractor, limited);

        for (int frame = 0; frame < 4; frame++) {
            limitedService.thumbnail(projects.findById(id), frame);
        }

        long cachedBytes = 0;
        long cachedFiles = 0;
        try (var files = Files.walk(thumbDir())) {
            List<Path> cached = files.filter(Files::isRegularFile).toList();
            cachedFiles = cached.size();
            for (Path path : cached) {
                cachedBytes += Files.size(path);
            }
        }
        assertThat(cachedBytes).isLessThanOrEqualTo(100);
        // 淘汰按最近使用顺序进行，不会把缓存清空
        assertThat(cachedFiles).isGreaterThan(0);
    }

    @Test
    void coalescesConcurrentRequestsForSameFrame() throws Exception {
        long id = readyProject();
        ClipProject project = projects.findById(id);
        extractor.delayMillis = 60;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<byte[]>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.thumbnail(project, 1);
            }));
        }
        start.countDown();
        for (Future<byte[]> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isNotEmpty();
        }
        pool.shutdownNow();

        // 同一帧的并发请求只抽一次帧
        assertThat(extractor.calls.get()).isEqualTo(1);
    }

    @Test
    void thumbnailUsesFrameTimestampFromIndex() throws Exception {
        long id = readyProject();

        service.thumbnail(projects.findById(id), 2);

        // 索引中第三帧的时间戳为 90ms
        assertThat(extractor.seekSeconds).isCloseTo(0.09d, org.assertj.core.data.Offset.offset(0.0001d));
    }

    /**
     * 缩略图缓存目录
     * 键包含资产 ID、文件大小与修改时间
     */
    private Path thumbDir() throws Exception {
        String fingerprint = "1-" + Files.size(videoFile) + "-" + Files.getLastModifiedTime(videoFile).toMillis();
        Path thumbs = tempDir.resolve("cache").resolve("thumbs");
        Path version;
        try (var stream = Files.list(thumbs)) {
            version = stream.filter(Files::isDirectory)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("未找到缩略图缓存版本目录"));
        }
        return version.resolve(fingerprint);
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
        volatile ClipThumbnailFormat format;
        /** 模拟抽帧耗时（毫秒） */
        volatile long delayMillis = 0;
        /** 写入的固定字节数，大于 0 时用于核对缓存容量 */
        volatile int contentBytes = 0;

        FakeExtractor() {
            super(new AppProperties());
        }

        @Override
        public void extract(Path source, double seekSeconds, Path output, ClipThumbnailFormat format) {
            calls.incrementAndGet();
            this.seekSeconds = seekSeconds;
            this.format = format;
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                Files.createDirectories(output.getParent());
                if (contentBytes > 0) {
                    byte[] data = new byte[contentBytes];
                    Arrays.fill(data, (byte) 'x');
                    Files.write(output, data);
                } else {
                    Files.writeString(output, "fake-png-frame-" + output.getFileName());
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
