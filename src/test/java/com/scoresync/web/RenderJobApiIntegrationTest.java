package com.scoresync.web;

import com.scoresync.domain.*;
import com.scoresync.repository.*;
import com.scoresync.service.ProjectService;
import com.scoresync.service.ScoreReplayService;
import com.scoresync.service.render.*;
import com.scoresync.support.RenderJobConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RenderJobApiIntegrationTest {

    private MockMvc mockMvc;
    private VideoSourceRepository sources;
    private VideoAssetRepository assets;
    private MatchProjectRepository projects;
    private long projectId;
    private FakeRenderJobService fakeRenderService;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("test-render-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
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
                CREATE TABLE match_project (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                  video_asset_id INTEGER NOT NULL REFERENCES video_asset(id), duration_ms INTEGER NOT NULL CHECK (duration_ms > 0),
                  player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL CHECK (target_wins >= 1),
                  scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC' CHECK (scoreboard_template IN ('CLASSIC','MODERN')),
                  first_server TEXT NOT NULL DEFAULT 'A' CHECK (first_server IN ('A','B')),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE score_event (
                  id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL REFERENCES match_project(id) ON DELETE CASCADE,
                  video_time_ms INTEGER NOT NULL CHECK (video_time_ms >= 0),
                  player_side TEXT NOT NULL CHECK (player_side IN ('A','B')), sequence_no INTEGER NOT NULL,
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE (match_id, sequence_no)
                )""");
        jdbc.execute("CREATE INDEX idx_score_event_match_timeline ON score_event(match_id, video_time_ms, sequence_no)");
        jdbc.execute("""
                CREATE TABLE render_job (
                  id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL,
                  template_code TEXT NOT NULL, status TEXT NOT NULL,
                  progress_percent INTEGER NOT NULL DEFAULT 0,
                  output_path TEXT NULL, failure_message TEXT NULL,
                  created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL
                )""");

        sources = new VideoSourceRepository(jdbc);
        assets = new VideoAssetRepository(jdbc);
        projects = new MatchProjectRepository(jdbc);
        ScoreEventRepository events = new ScoreEventRepository(jdbc);
        RenderJobRepository renderJobs = new RenderJobRepository(jdbc);

        VideoSource source = sources.insert("/videos");
        long videoId = assets.insert(source.id(), "/videos/test.mp4", "测试视频", 1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);
        projectId = projects.insert("决赛", videoId, 60000L, "甲", "乙", 2, com.scoresync.domain.ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A);

        ScoreReplayService replayService = new ScoreReplayService();
        ProjectService projectService = new ProjectService(projects, assets, events, renderJobs, replayService);
        List<ScoreboardTemplateRenderer> renderers = List.of(new ClassicScoreboardRenderer(), new ModernScoreboardRenderer());
        ScoreboardPreviewService previewService = new ScoreboardPreviewService(renderers);
        ScoreboardSnapshotService snapshotService = new ScoreboardSnapshotService();

        fakeRenderService = new FakeRenderJobService();

        ProjectController projectController = new ProjectController(projectService);
        RenderJobController renderJobController = new RenderJobController(previewService, snapshotService, projectService, fakeRenderService);

        mockMvc = MockMvcBuilders.standaloneSetup(projectController, renderJobController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void updateScoreboardTemplate_shouldReturnUpdatedTemplate() throws Exception {
        mockMvc.perform(patch("/api/projects/{id}/scoreboard-template", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateCode\":\"MODERN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.scoreboardTemplate").value("MODERN"));
    }

    @Test
    void getScoreboardPreview_shouldReturnPngImage() throws Exception {
        mockMvc.perform(get("/api/projects/{id}/scoreboard-preview", projectId)
                        .param("template", "MODERN")
                        .param("timeMs", "0")
                        .param("renderWidth", "960"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).isNotEmpty());
    }

    @Test
    void createRenderJobReturnsAcceptedWithQueuedStatus() throws Exception {
        long jobId = fakeRenderService.nextJobId();
        mockMvc.perform(post("/api/projects/{id}/render-jobs", projectId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.matchId").value(projectId));
    }

    @Test
    void getActiveRenderJobReturnsActiveJob() throws Exception {
        long jobId = fakeRenderService.seedActive(projectId);

        mockMvc.perform(get("/api/render-jobs/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchId").value(projectId))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void getActiveRenderJobReturnsNoContentWhenIdle() throws Exception {
        mockMvc.perform(get("/api/render-jobs/active"))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelRenderJobReturnsCancelledStatus() throws Exception {
        long jobId = fakeRenderService.seedActive(projectId);

        mockMvc.perform(post("/api/render-jobs/{id}/cancel", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void secondCreateRenderJobReturnsConflict() throws Exception {
        fakeRenderService.seedActive(projectId);
        fakeRenderService.active.set(true);

        mockMvc.perform(post("/api/projects/{id}/render-jobs", projectId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENDER_JOB_ACTIVE"));
    }

    static class FakeRenderJobService extends RenderJobService {
        final AtomicLong idSeq = new AtomicLong(0);
        final java.util.Map<Long, RenderJob> jobs = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(false);

        FakeRenderJobService() {
            super(null, null, null, null, null, null, null, null, null);
        }

        long nextJobId() {
            long id = idSeq.incrementAndGet();
            jobs.put(id, new RenderJob(id, 0, ScoreboardTemplate.CLASSIC, RenderJobStatus.QUEUED, 0,
                    null, null, Instant.now(), null, null));
            return id;
        }

        long seedActive(long matchId) {
            long id = idSeq.incrementAndGet();
            jobs.put(id, new RenderJob(id, matchId, ScoreboardTemplate.CLASSIC, RenderJobStatus.RUNNING, 30,
                    null, null, Instant.now(), Instant.now(), null));
            active.set(true);
            return id;
        }

        @Override
        public RenderJob start(long matchId) {
            if (active.get()) {
                throw new RenderJobConflictException("RENDER_JOB_ACTIVE", "已有导出任务正在执行");
            }
            long id = idSeq.incrementAndGet();
            RenderJob job = new RenderJob(id, matchId, ScoreboardTemplate.CLASSIC, RenderJobStatus.QUEUED, 0,
                    null, null, Instant.now(), null, null);
            jobs.put(id, job);
            active.set(true);
            return job;
        }

        @Override
        public RenderJob cancel(long jobId) {
            RenderJob job = jobs.get(jobId);
            RenderJob cancelled = new RenderJob(jobId, job.matchId(), job.templateCode(),
                    RenderJobStatus.CANCELLED, job.progressPercent(), job.outputPath(), job.failureMessage(),
                    job.createdAt(), job.startedAt(), Instant.now());
            jobs.put(jobId, cancelled);
            active.set(false);
            return cancelled;
        }

        @Override
        public RenderJob get(long jobId) {
            return jobs.get(jobId);
        }

        @Override
        public Optional<RenderJob> findActive() {
            return jobs.values().stream()
                    .filter(j -> j.status() == RenderJobStatus.QUEUED || j.status() == RenderJobStatus.RUNNING)
                    .findFirst();
        }
    }
}
