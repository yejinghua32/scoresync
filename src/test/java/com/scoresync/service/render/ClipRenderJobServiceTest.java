package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.*;
import com.scoresync.repository.*;
import com.scoresync.service.ClipSourceValidator;
import com.scoresync.service.VideoLibraryService;
import com.scoresync.support.DomainValidationException;
import com.scoresync.support.RenderJobConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClipRenderJobServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private ClipProjectRepository projects;
    private ClipSegmentRepository segments;
    private ClipRenderJobRepository jobs;
    private VideoAssetRepository assets;
    private VideoLibraryService videoLibrary;
    private ExportOccupancyService occupancy;
    private ClipSourceValidator validator;
    private ClipFrameIndexService indexService;
    private FakeRenderService fakeRender;
    private AppProperties appProperties;
    private Path videoFile;
    private long projectId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("clip-render-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE video_source (
                  id INTEGER PRIMARY KEY, root_path TEXT NOT NULL UNIQUE,
                  enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE video_asset (
                  id INTEGER PRIMARY KEY, source_id INTEGER NULL REFERENCES video_source(id),
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
        jdbc.execute("""
                CREATE TABLE clip_segment (
                  id INTEGER PRIMARY KEY,
                  project_id INTEGER NOT NULL REFERENCES clip_project(id) ON DELETE CASCADE,
                  segment_type TEXT NOT NULL CHECK (segment_type IN ('KEEP','DROP')),
                  start_frame INTEGER NOT NULL CHECK (start_frame >= 0),
                  end_frame INTEGER NOT NULL CHECK (end_frame > start_frame),
                  featured INTEGER NOT NULL DEFAULT 0 CHECK (featured IN (0,1)),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE clip_render_job (
                  id INTEGER PRIMARY KEY, project_id INTEGER NOT NULL REFERENCES clip_project(id),
                  status TEXT NOT NULL, progress_percent INTEGER NOT NULL DEFAULT 0,
                  output_path TEXT NULL, output_asset_id INTEGER NULL, failure_message TEXT NULL,
                  created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE render_job (
                  id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL,
                  template_code TEXT NOT NULL, status TEXT NOT NULL,
                  progress_percent INTEGER NOT NULL DEFAULT 0,
                  output_path TEXT NULL, failure_message TEXT NULL,
                  created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL
                )""");

        projects = new ClipProjectRepository(jdbc);
        segments = new ClipSegmentRepository(jdbc);
        jobs = new ClipRenderJobRepository(jdbc);
        assets = new VideoAssetRepository(jdbc);
        VideoSourceRepository sources = new VideoSourceRepository(jdbc);
        videoLibrary = new VideoLibraryService(sources, assets,
                new MatchProjectRepository(jdbc), projects);
        occupancy = new ExportOccupancyService(new RenderJobRepository(jdbc), jobs);

        appProperties = new AppProperties();
        appProperties.getClip().setWorkDir(tempDir.resolve("clip-work").toString());
        appProperties.getClip().setCacheDir(tempDir.resolve("cache").toString());
        appProperties.getRender().setOutputDir(tempDir.resolve("exports").toString());

        videoFile = tempDir.resolve("source.mp4");
        Files.writeString(videoFile, "fake-video");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                        + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, ?, 'source.mp4', ?, ?, "
                        + "'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')",
                videoFile.toString(), Files.size(videoFile),
                Files.getLastModifiedTime(videoFile).toMillis());

        projectId = projects.insert("第一集", 1L, 10, 5,
                Files.size(videoFile), Files.getLastModifiedTime(videoFile).toMillis());
        segments.insert(projectId, ClipSegmentType.KEEP, 10, 20);

        validator = new ClipSourceValidator(assets);
        indexService = new ClipFrameIndexService(projects, validator, new FakeIndexBuilder(), appProperties);
        indexService.requestBuild(projects.findById(projectId));
        for (int i = 0; i < 200; i++) {
            if (projects.findById(projectId).frameIndexStatus() == ClipFrameIndexStatus.READY) {
                break;
            }
            Thread.sleep(25);
        }
        assertThat(projects.findById(projectId).frameIndexStatus()).isEqualTo(ClipFrameIndexStatus.READY);

        fakeRender = new FakeRenderService(appProperties);
    }

    @Test
    void rejectsExportWhenNoKeepSegmentExists() {
        ClipRenderJobService service = service();
        segments.deleteByProjectId(projectId);

        assertThatThrownBy(() -> service.start(projectId))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("没有保留任何片段");
    }

    @Test
    void rejectsFeaturedExportWhenNoFeaturedSegmentExists() {
        ClipRenderJobService service = service();
        segments.deleteByProjectId(projectId);
        segments.insert(projectId, ClipSegmentType.KEEP, 0, 10);

        assertThatThrownBy(() -> service.start(projectId, ClipExportMode.FEATURED))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("没有精选的保留片段");
        // 集合为空时不创建任务
        assertThat(jobs.findByStatus(ClipRenderJobStatus.QUEUED)).isEmpty();
    }

    @Test
    void featuredExportOnlyUsesFeaturedKeepSegments() throws Exception {
        ClipRenderJobService service = service();
        fakeRender.writeOutput = true;
        segments.deleteByProjectId(projectId);
        segments.insert(projectId, ClipSegmentType.KEEP, 0, 10, true);
        segments.insert(projectId, ClipSegmentType.KEEP, 20, 30, false);
        segments.insert(projectId, ClipSegmentType.DROP, 40, 50, false);

        ClipRenderJob job = service.start(projectId, ClipExportMode.FEATURED);
        fakeRender.awaitEntered();
        fakeRender.release();
        waitForStatus(job.id(), ClipRenderJobStatus.SUCCEEDED);

        // 只冻结精选的保留片段
        assertThat(fakeRender.filterScript).contains("gte(t\\,0.000000)*lt(t\\,0.333333)");
        assertThat(fakeRender.filterScript).doesNotContain("gte(t\\,0.666667)*lt(t\\,1.000000)");
        assertThat(fakeRender.filterScript).doesNotContain("gte(t\\,1.333333)*lt(t\\,1.666667)");
    }

    @Test
    void allExportKeepsNonFeaturedKeepSegments() throws Exception {
        ClipRenderJobService service = service();
        fakeRender.writeOutput = true;
        segments.deleteByProjectId(projectId);
        segments.insert(projectId, ClipSegmentType.KEEP, 0, 10, true);
        segments.insert(projectId, ClipSegmentType.KEEP, 20, 30, false);

        ClipRenderJob job = service.start(projectId, ClipExportMode.ALL);
        fakeRender.awaitEntered();
        fakeRender.release();
        waitForStatus(job.id(), ClipRenderJobStatus.SUCCEEDED);

        // 全部范围包含精选与非精选的保留片段
        assertThat(fakeRender.filterScript).contains("gte(t\\,0.000000)*lt(t\\,0.333333)");
        assertThat(fakeRender.filterScript).contains("gte(t\\,0.666667)*lt(t\\,1.000000)");
    }

    @Test
    void editingSegmentsAfterStartDoesNotChangeFrozenExport() throws Exception {
        ClipRenderJobService service = service();
        fakeRender.writeOutput = true;
        segments.deleteByProjectId(projectId);
        segments.insert(projectId, ClipSegmentType.KEEP, 0, 10, true);

        ClipRenderJob job = service.start(projectId, ClipExportMode.FEATURED);
        fakeRender.awaitEntered();

        // 任务开始后改动精选与边界，不影响已经冻结的本次导出
        segments.deleteByProjectId(projectId);
        segments.insert(projectId, ClipSegmentType.KEEP, 100, 200, false);

        fakeRender.release();
        waitForStatus(job.id(), ClipRenderJobStatus.SUCCEEDED);
        assertThat(service.get(job.id()).outputAssetId()).isNotNull();
        assertThat(fakeRender.filterScript).contains("gte(t\\,0.000000)*lt(t\\,0.333333)");
        assertThat(fakeRender.filterScript).doesNotContain("gte(t\\,3.333333)");
    }

    @Test
    void startCreatesQueuedJobAndRunsIt() throws Exception {
        ClipRenderJobService service = service();
        fakeRender.writeOutput = true;

        ClipRenderJob job = service.start(projectId);
        fakeRender.awaitEntered();

        assertThat(job.status()).isEqualTo(ClipRenderJobStatus.QUEUED);
        assertThat(service.get(job.id()).status()).isEqualTo(ClipRenderJobStatus.RUNNING);

        fakeRender.release();
        waitForStatus(job.id(), ClipRenderJobStatus.SUCCEEDED);
    }

    @Test
    void successfulExportRegistersNewVideoAsset() throws Exception {
        ClipRenderJobService service = service();
        fakeRender.writeOutput = true;

        ClipRenderJob job = service.start(projectId);
        fakeRender.awaitEntered();
        fakeRender.release();
        waitForStatus(job.id(), ClipRenderJobStatus.SUCCEEDED);

        ClipRenderJob finished = service.get(job.id());
        assertThat(finished.outputAssetId()).isNotNull();
        assertThat(finished.outputPath()).isNotNull();

        VideoAsset registered = assets.findById(finished.outputAssetId());
        assertThat(registered.absolutePath()).endsWith(".mp4");
        assertThat(registered.originType()).isEqualTo(VideoOriginType.SCANNED);
    }

    @Test
    void repeatedExportCreatesSeparateFilesAndAssets() throws Exception {
        ClipRenderJobService service = service();
        fakeRender.writeOutput = true;

        ClipRenderJob first = service.start(projectId);
        fakeRender.awaitEntered();
        fakeRender.release();
        waitForStatus(first.id(), ClipRenderJobStatus.SUCCEEDED);

        fakeRender.reset();
        ClipRenderJob second = service.start(projectId);
        fakeRender.awaitEntered();
        fakeRender.release();
        waitForStatus(second.id(), ClipRenderJobStatus.SUCCEEDED);

        ClipRenderJob firstDone = service.get(first.id());
        ClipRenderJob secondDone = service.get(second.id());
        assertThat(firstDone.outputPath()).isNotEqualTo(secondDone.outputPath());
        assertThat(firstDone.outputAssetId()).isNotEqualTo(secondDone.outputAssetId());
        // 源视频 + 两次导出各自的产物
        assertThat(assets.findAll()).hasSize(3);
    }

    @Test
    void failedExportDoesNotRegisterAsset() throws Exception {
        ClipRenderJobService service = service();
        fakeRender.writeOutput = false;

        ClipRenderJob job = service.start(projectId);
        fakeRender.awaitEntered();
        fakeRender.release();
        waitForStatus(job.id(), ClipRenderJobStatus.FAILED);

        assertThat(service.get(job.id()).outputAssetId()).isNull();
        assertThat(assets.findAll()).hasSize(1);
    }

    @Test
    void cancelMarksJobCancelledAndRemovesWorkDir() throws Exception {
        ClipRenderJobService service = service();

        ClipRenderJob job = service.start(projectId);
        fakeRender.awaitEntered();

        Path jobWorkDir = Path.of(appProperties.getClip().getWorkDir()).resolve(String.valueOf(job.id()));
        Files.createDirectories(jobWorkDir);
        Files.writeString(jobWorkDir.resolve("temp.txt"), "stub");

        service.cancel(job.id());

        assertThat(service.get(job.id()).status()).isEqualTo(ClipRenderJobStatus.CANCELLED);
        assertThat(Files.exists(jobWorkDir)).isFalse();

        fakeRender.release();
    }

    @Test
    void clipExportConflictsWithScoreExport() throws Exception {
        ClipRenderJobService service = service();
        // 记分导出任务处于活跃状态
        jdbc.update("INSERT INTO render_job (id, match_id, template_code, status, progress_percent, "
                + "output_path, failure_message, created_at, started_at, finished_at) "
                + "VALUES (1, 1, 'CLASSIC', 'RUNNING', 10, NULL, NULL, '2026-08-04T00:00:00Z', NULL, NULL)");

        assertThatThrownBy(() -> service.start(projectId))
                .isInstanceOf(RenderJobConflictException.class)
                .hasFieldOrPropertyWithValue("code", "CLIP_RENDER_JOB_ACTIVE")
                .hasMessage("已有导出任务正在执行");
    }

    @Test
    void scoreExportConflictsWithClipExport() throws Exception {
        ClipRenderJobService service = service();
        fakeRender.writeOutput = true;

        ClipRenderJob job = service.start(projectId);
        fakeRender.awaitEntered();

        RenderJobService scoreService = new RenderJobService(new RenderJobRepository(jdbc),
                null, null, null, null, null, null, fakeRender, appProperties, occupancy);

        assertThatThrownBy(() -> scoreService.start(1L))
                .isInstanceOf(RenderJobConflictException.class)
                .hasFieldOrPropertyWithValue("code", "RENDER_JOB_ACTIVE");

        fakeRender.release();
        waitForStatus(job.id(), ClipRenderJobStatus.SUCCEEDED);
    }

    @Test
    void failInterruptedJobsMarksQueuedAndRunningAsFailed() throws Exception {
        ClipRenderJobService service = service();
        ClipRenderJob job = service.start(projectId);
        fakeRender.awaitEntered();

        service.failInterruptedJobs();

        assertThat(jobs.findByStatus(ClipRenderJobStatus.FAILED))
                .extracting(ClipRenderJob::id).contains(job.id());

        fakeRender.release();
    }

    private ClipRenderJobService service() {
        return new ClipRenderJobService(jobs, projects, segments, assets, validator,
                indexService,
                new ClipFfmpegCommandFactory(appProperties),
                fakeRender,
                new FixedProbeService(appProperties),
                videoLibrary,
                occupancy,
                appProperties);
    }

    private void waitForStatus(long jobId, ClipRenderJobStatus expected) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (jobs.findById(jobId).status() == expected) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(jobs.findById(jobId).status()).isEqualTo(expected);
    }

    /**
     * 假探测服务：返回固定时长与音轨
     */
    static class FixedProbeService extends FfmpegProbeService {
        FixedProbeService(AppProperties appProperties) {
            super(appProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public VideoProbeResult probe(Path source) {
            return new VideoProbeResult(1920, 1080, 6000, "30/1", true);
        }
    }

    /**
     * 假索引构建器
     */
    static class FakeIndexBuilder extends ClipFrameIndexBuilder {
        FakeIndexBuilder() {
            super(new AppProperties());
        }

        @Override
        public ClipFrameIndex build(Path source) {
            long[] pts = new long[60];
            for (int i = 0; i < pts.length; i++) {
                pts[i] = Math.round(i * 1_000_000d / 30d);
            }
            return new ClipFrameIndex(pts, new long[0], 0L);
        }
    }

    /**
     * 假渲染服务：不启动真实 FFmpeg
     */
    static class FakeRenderService extends FfmpegRenderService {
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile boolean writeOutput = true;
        /** 最近一次渲染使用的滤镜脚本内容，用于检查冻结的帧区间 */
        volatile String filterScript = "";

        FakeRenderService(AppProperties appProperties) {
            super(appProperties);
        }

        @Override
        public void execute(long jobId, Path workDir, long durationMs, FfmpegCommand command,
                            Path finalOutput, IntConsumer progressCallback) {
            entered.countDown();
            try {
                filterScript = Files.readString(command.filterScript());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!writeOutput) {
                throw new IllegalStateException("FFmpeg 退出码: 1");
            }
            try {
                Files.createDirectories(finalOutput.getParent());
                Files.writeString(finalOutput, "fake-mp4");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
        }

        void release() throws InterruptedException {
            release.countDown();
            Thread.sleep(50);
        }

        void reset() throws InterruptedException {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
    }
}
