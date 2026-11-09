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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void queuesMultipleProjectsInCreationOrder() throws Exception {
        FakeIndexBuilder builder = new FakeIndexBuilder();
        service = new ClipFrameIndexService(projects,
                new ClipSourceValidator(new VideoAssetRepository(jdbc)), builder, properties());
        long first = createProject("第一集", 1L);
        long second = createProject("第二集", createSecondAsset());

        service.requestBuild(projects.findById(first));
        service.requestBuild(projects.findById(second));
        waitForStatus(second, ClipFrameIndexStatus.READY);

        assertThat(projects.findById(first).frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.READY);
        assertThat(builder.startedFiles).containsExactly("source.mp4", "source-2.mp4");
    }

    @Test
    void failureDoesNotBlockLaterProject() throws Exception {
        CountingFailingBuilder builder = new CountingFailingBuilder();
        service = new ClipFrameIndexService(projects,
                new ClipSourceValidator(new VideoAssetRepository(jdbc)), builder, properties());
        long first = createProject();
        long second = createProject();
        builder.failId = first;
        builder.failed = new CountDownLatch(1);

        service.requestBuild(projects.findById(first));
        service.requestBuild(projects.findById(second));
        assertThat(builder.failed.await(5, TimeUnit.SECONDS)).isTrue();
        waitForStatus(second, ClipFrameIndexStatus.READY);

        assertThat(projects.findById(first).frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.FAILED);
        assertThat(projects.findById(second).frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.READY);
        assertThat(builder.calls.get()).isEqualTo(2);
    }

    @Test
    void duplicateRequestsOnlyBuildOnce() throws Exception {
        FakeIndexBuilder builder = new FakeIndexBuilder();
        service = new ClipFrameIndexService(projects,
                new ClipSourceValidator(new VideoAssetRepository(jdbc)), builder, properties());
        long id = createProject();

        service.requestBuild(projects.findById(id));
        service.requestBuild(projects.findById(id));
        waitForStatus(id, ClipFrameIndexStatus.READY);
        service.requestBuild(projects.findById(id));

        assertThat(builder.calls.get()).isEqualTo(1);
    }

    @Test
    void startupRestoresBuildingAndPendingProjectsInIdOrder() throws Exception {
        jdbc.update("DELETE FROM clip_project");
        long first = createProject("第一集", 1L);
        long second = createProject("第二集", createSecondAsset());
        projects.updateFrameIndex(first, ClipFrameIndexStatus.BUILDING, null);
        projects.updateFrameIndex(second, ClipFrameIndexStatus.PENDING, null);

        FakeIndexBuilder builder = new FakeIndexBuilder();
        service = new ClipFrameIndexService(projects,
                new ClipSourceValidator(new VideoAssetRepository(jdbc)), builder, properties());
        service.run();
        waitForStatus(second, ClipFrameIndexStatus.READY);

        assertThat(builder.startedFiles).containsExactly("source.mp4", "source-2.mp4");
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
        return createProject("第一集", 1L);
    }

    private long createProject(String name, long videoAssetId) throws IOException {
        Path file = videoAssetId == 1L ? videoFile : Path.of(jdbc.queryForObject(
                "SELECT absolute_path FROM video_asset WHERE id = ?", String.class, videoAssetId));
        return projects.insert(name, videoAssetId, 10, 5,
                Files.size(file), Files.getLastModifiedTime(file).toMillis());
    }

    private long createSecondAsset() throws IOException {
        Path second = tempDir.resolve("source-2.mp4");
        Files.writeString(second, "fake-video-2");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                        + "modified_at, origin_type, created_at, updated_at) VALUES (2, NULL, ?, 'source-2.mp4', ?, ?, "
                        + "'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')",
                second.toString(), Files.size(second), Files.getLastModifiedTime(second).toMillis());
        return 2L;
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
        final List<String> startedFiles = new CopyOnWriteArrayList<>();

        FakeIndexBuilder() {
            super(new AppProperties());
        }

        @Override
        public ClipFrameIndex build(Path source) {
            calls.incrementAndGet();
            startedFiles.add(source.getFileName().toString());
            return new ClipFrameIndex(
                    new long[]{0L, 40_000L, 90_000L, 160_000L},
                    new long[]{0L, 0L, 0L, 40_000L},
                    0L);
        }
    }

    /**
     * 假索引构建器：始终失败
     */
    static class CountingFailingBuilder extends ClipFrameIndexBuilder {
        final AtomicInteger calls = new AtomicInteger();
        long failId = -1;
        CountDownLatch failed = new CountDownLatch(1);

        CountingFailingBuilder() {
            super(new AppProperties());
        }

        @Override
        public ClipFrameIndex build(Path source) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                failed.countDown();
                throw new IllegalStateException("索引建立失败");
            }
            return new ClipFrameIndex(
                    new long[]{0L, 40_000L},
                    new long[]{0L, 0L}, 0L);
        }
    }

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
