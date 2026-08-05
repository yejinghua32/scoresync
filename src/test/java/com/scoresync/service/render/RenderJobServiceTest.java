package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.RenderJob;
import com.scoresync.domain.RenderJobStatus;
import com.scoresync.domain.VideoProbeResult;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.RenderJobRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.RenderJobConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderJobServiceTest {

    private RenderJobRepository repository;
    private RenderJobService service;
    private BlockingFakeRenderService fakeRender;
    private long matchId;
    private AppProperties appProperties;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("render-service-test", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE video_source (" +
                "  id INTEGER PRIMARY KEY, root_path TEXT NOT NULL UNIQUE," +
                "  enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL" +
                ")");
        jdbc.execute("CREATE TABLE video_asset (" +
                "  id INTEGER PRIMARY KEY, source_id INTEGER NULL," +
                "  absolute_path TEXT NOT NULL, display_name TEXT NOT NULL," +
                "  file_size INTEGER NOT NULL, modified_at INTEGER NOT NULL," +
                "  origin_type TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL" +
                ")");
        jdbc.execute("CREATE TABLE match_project (" +
                "  id INTEGER PRIMARY KEY, name TEXT NOT NULL," +
                "  video_asset_id INTEGER NOT NULL, duration_ms INTEGER NOT NULL," +
                "  player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL," +
                "  scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC'," +
                "  first_server TEXT NOT NULL DEFAULT 'A' CHECK (first_server IN ('A','B'))," +
                "  created_at TEXT NOT NULL, updated_at TEXT NOT NULL" +
                ")");
        jdbc.execute("CREATE TABLE score_event (" +
                "  id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL," +
                "  video_time_ms INTEGER NOT NULL, player_side TEXT NOT NULL," +
                "  sequence_no INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL" +
                ")");
        jdbc.execute("CREATE TABLE render_job (" +
                "  id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL," +
                "  template_code TEXT NOT NULL, status TEXT NOT NULL," +
                "  progress_percent INTEGER NOT NULL DEFAULT 0," +
                "  output_path TEXT NULL, failure_message TEXT NULL," +
                "  created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL" +
                ")");

        jdbc.update("INSERT INTO video_source (id, root_path, enabled, created_at, updated_at) VALUES (1, '/v', 1, '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, modified_at, origin_type, created_at, updated_at) VALUES (1, 1, '/v/test.mp4', '测试', 1000, 0, 'PICKED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        jdbc.update("INSERT INTO match_project (id, name, video_asset_id, duration_ms, player_a, player_b, target_wins, scoreboard_template, created_at, updated_at) VALUES (1, '测试', 1, 5000, '甲', '乙', 2, 'CLASSIC', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        matchId = 1L;

        repository = new RenderJobRepository(jdbc);
        MatchProjectRepository projects = new MatchProjectRepository(jdbc);
        VideoAssetRepository videos = new VideoAssetRepository(jdbc);
        ScoreEventRepository events = new ScoreEventRepository(jdbc);

        appProperties = new AppProperties();
        appProperties.getRender().setWorkDir(tempDir.resolve("render-work").toString());
        appProperties.getRender().setOutputDir(tempDir.resolve("exports").toString());

        fakeRender = new BlockingFakeRenderService(appProperties);

        FfmpegProbeService probeService = new FixedProbeService(appProperties);
        ScoreboardSnapshotService snapshotService = new ScoreboardSnapshotService();
        List<ScoreboardTemplateRenderer> renderers = List.of(new ClassicScoreboardRenderer(), new ModernScoreboardRenderer());
        RenderPlanService planService = new RenderPlanService(renderers, snapshotService);
        FfmpegCommandFactory commandFactory = new FfmpegCommandFactory(appProperties);

        service = new RenderJobService(repository, projects, videos, events,
                probeService, planService, commandFactory, fakeRender, appProperties);
    }

    @Test
    void rejectsSecondJobWhileFirstJobIsRunning() throws Exception {
        RenderJob first = service.start(matchId);
        assertThat(first.status()).isEqualTo(RenderJobStatus.QUEUED);
        fakeRender.awaitEntered();

        assertThatThrownBy(() -> service.start(matchId))
                .isInstanceOf(RenderJobConflictException.class)
                .hasFieldOrPropertyWithValue("code", "RENDER_JOB_ACTIVE")
                .hasMessage("已有导出任务正在执行");

        fakeRender.release();
    }

    @Test
    void reportsProgressFromFfmpegOutTimeUs() throws Exception {
        RenderJob job = service.start(matchId);
        fakeRender.awaitEntered();

        RenderJob stored = service.get(job.id());
        assertThat(stored.status()).isEqualTo(RenderJobStatus.RUNNING);

        fakeRender.reportProgress(2_500_000L, 5_000L);

        RenderJob progressed = service.get(job.id());
        assertThat(progressed.progressPercent()).isEqualTo(50);

        fakeRender.release();
    }

    @Test
    void marksRunningJobCancelledAndDeletesWorkDirectory() throws Exception {
        RenderJob job = service.start(matchId);
        fakeRender.awaitEntered();

        Path jobWorkDir = Path.of(appProperties.getRender().getWorkDir()).resolve(String.valueOf(job.id()));
        Files.createDirectories(jobWorkDir);
        Files.writeString(jobWorkDir.resolve("segment-0000.png"), "stub");

        service.cancel(job.id());

        RenderJob cancelled = service.get(job.id());
        assertThat(cancelled.status()).isEqualTo(RenderJobStatus.CANCELLED);
        assertThat(Files.exists(jobWorkDir)).isFalse();

        fakeRender.release();
    }

    @Test
    void cancelFailsForAlreadyFinishedJob() {
        RenderJob job = service.start(matchId);
        repository.update(new RenderJob(job.id(), job.matchId(), job.templateCode(),
                RenderJobStatus.SUCCEEDED, 100, "/x.mp4", null,
                job.createdAt(), job.startedAt(), job.finishedAt()));

        assertThatThrownBy(() -> service.cancel(job.id()))
                .isInstanceOf(RenderJobConflictException.class);
    }

    @Test
    void failInterruptedJobsMarksQueuedAsFailed() {
        RenderJob job = service.start(matchId);
        repository.update(new RenderJob(job.id(), job.matchId(), job.templateCode(),
                RenderJobStatus.QUEUED, 0, null, null,
                job.createdAt(), null, null));

        service.failInterruptedJobs();

        assertThat(repository.findByStatus(RenderJobStatus.FAILED))
                .extracting(RenderJob::id).contains(job.id());
    }

    static class FixedProbeService extends FfmpegProbeService {
        FixedProbeService(AppProperties appProperties) {
            super(appProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public VideoProbeResult probe(Path source) {
            return new VideoProbeResult(1920, 1080, 5000, "30000/1001", true);
        }
    }

    static class BlockingFakeRenderService extends FfmpegRenderService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile IntConsumer callback;

        BlockingFakeRenderService(AppProperties appProperties) {
            super(appProperties);
        }

        @Override
        public void execute(long jobId, com.scoresync.domain.RenderPlan plan,
                            FfmpegCommand command, Path finalOutput, IntConsumer progressCallback) {
            this.callback = progressCallback;
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void awaitEntered() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
        }

        void release() throws InterruptedException {
            release.countDown();
            Thread.sleep(100);
        }

        void reportProgress(long outTimeUs, long durationMs) {
            if (callback != null) {
                callback.accept(FfmpegRenderService.progressPercent(outTimeUs, durationMs));
            }
        }
    }
}
