package com.scoresync.web;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.*;
import com.scoresync.repository.*;
import com.scoresync.service.ClipSourceValidator;
import com.scoresync.service.VideoLibraryService;
import com.scoresync.service.render.*;
import com.scoresync.support.RenderJobConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClipRenderJobApiIntegrationTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private ClipRenderJobRepository jobs;
    private ClipProjectRepository projects;
    private ClipSegmentRepository segments;
    private VideoAssetRepository assets;
    private FakeRenderService fakeRender;
    private long projectId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("clip-render-api-db", ".db");
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
        ClipSegmentRepository segments = new ClipSegmentRepository(jdbc);
        this.segments = segments;
        jobs = new ClipRenderJobRepository(jdbc);
        assets = new VideoAssetRepository(jdbc);
        VideoSourceRepository sources = new VideoSourceRepository(jdbc);
        VideoLibraryService videoLibrary = new VideoLibraryService(sources, assets,
                new MatchProjectRepository(jdbc), projects);
        ExportOccupancyService occupancy = new ExportOccupancyService(new RenderJobRepository(jdbc), jobs);

        AppProperties properties = new AppProperties();
        properties.getClip().setWorkDir(tempDir.resolve("clip-work").toString());
        properties.getClip().setCacheDir(tempDir.resolve("cache").toString());
        properties.getRender().setOutputDir(tempDir.resolve("exports").toString());

        Path videoFile = tempDir.resolve("source.mp4");
        Files.writeString(videoFile, "fake-video");
        jdbc.update("INSERT INTO video_asset (id, source_id, absolute_path, display_name, file_size, "
                        + "modified_at, origin_type, created_at, updated_at) VALUES (1, NULL, ?, 'source.mp4', ?, ?, "
                        + "'SCANNED', '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')",
                videoFile.toString(), Files.size(videoFile),
                Files.getLastModifiedTime(videoFile).toMillis());

        projectId = projects.insert("第一集", 1L, 10, 5,
                Files.size(videoFile), Files.getLastModifiedTime(videoFile).toMillis());
        segments.insert(projectId, ClipSegmentType.KEEP, 10, 20);

        ClipSourceValidator validator = new ClipSourceValidator(assets);
        ClipFrameIndexService indexService = new ClipFrameIndexService(projects, validator,
                new FakeIndexBuilder(), properties);
        indexService.requestBuild(projects.findById(projectId));
        for (int i = 0; i < 200; i++) {
            if (projects.findById(projectId).frameIndexStatus() == ClipFrameIndexStatus.READY) {
                break;
            }
            Thread.sleep(25);
        }

        fakeRender = new FakeRenderService(properties);
        ClipRenderJobService service = new ClipRenderJobService(jobs, projects, segments, assets,
                validator, indexService, new ClipFfmpegCommandFactory(properties), fakeRender,
                new FixedProbeService(properties), videoLibrary, occupancy, properties);

        mockMvc = MockMvcBuilders.standaloneSetup(new ClipRenderJobController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createClipRenderJobReturnsAcceptedWithQueuedStatus() throws Exception {
        mockMvc.perform(post("/api/clip-projects/{id}/render-jobs", projectId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.projectId").value(projectId));

        fakeRender.release();
    }

    @Test
    void getClipRenderJobReturnsJobDetail() throws Exception {
        long jobId = createJob();

        mockMvc.perform(get("/api/clip-render-jobs/{id}", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.projectId").value(projectId));

        fakeRender.release();
    }

    @Test
    void getActiveClipRenderJobReturnsActiveJob() throws Exception {
        long jobId = createJob();

        mockMvc.perform(get("/api/clip-render-jobs/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        fakeRender.release();
    }

    @Test
    void getActiveClipRenderJobReturnsNoContentWhenIdle() throws Exception {
        mockMvc.perform(get("/api/clip-render-jobs/active"))
                .andExpect(status().isNoContent());
    }

    @Test
    void cancelClipRenderJobReturnsCancelledStatus() throws Exception {
        long jobId = createJob();

        mockMvc.perform(post("/api/clip-render-jobs/{id}/cancel", jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        fakeRender.release();
    }

    @Test
    void secondClipRenderJobReturnsConflict() throws Exception {
        createJob();

        mockMvc.perform(post("/api/clip-projects/{id}/render-jobs", projectId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLIP_RENDER_JOB_ACTIVE"));

        fakeRender.release();
    }

    @Test
    void createClipRenderJobWithoutBodyKeepsAllKeepSegments() throws Exception {
        mockMvc.perform(post("/api/clip-projects/{id}/render-jobs", projectId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        fakeRender.release();
    }

    @Test
    void createClipRenderJobRejectsUnknownMode() throws Exception {
        mockMvc.perform(post("/api/clip-projects/{id}/render-jobs", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SOMETHING\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("导出范围只能是 ALL 或 FEATURED")));

        // 非法范围不产生任务
        assertThat(jobs.findByStatus(ClipRenderJobStatus.QUEUED)).isEmpty();
    }

    @Test
    void createFeaturedClipRenderJobRejectsWhenNoFeaturedSegmentExists() throws Exception {
        mockMvc.perform(post("/api/clip-projects/{id}/render-jobs", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FEATURED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value(containsString("没有精选的保留片段")));

        assertThat(jobs.findByStatus(ClipRenderJobStatus.QUEUED)).isEmpty();
    }

    @Test
    void createFeaturedClipRenderJobAcceptsWhenFeaturedSegmentExists() throws Exception {
        segments.insert(projectId, ClipSegmentType.KEEP, 40, 50, true);

        mockMvc.perform(post("/api/clip-projects/{id}/render-jobs", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"FEATURED\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        fakeRender.release();
    }

    @Test
    void downloadSucceededJobReturnsVideoFile() throws Exception {
        long jobId = createJob();
        fakeRender.release();
        waitForStatus(jobId, ClipRenderJobStatus.SUCCEEDED);

        mockMvc.perform(get("/api/clip-render-jobs/{id}/download", jobId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("video/mp4")));
    }

    @Test
    void downloadUnfinishedJobReturnsConflict() throws Exception {
        long jobId = createJob();

        mockMvc.perform(get("/api/clip-render-jobs/{id}/download", jobId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENDER_JOB_NOT_DOWNLOADABLE"));

        fakeRender.release();
    }

    private long createJob() throws Exception {
        String body = mockMvc.perform(post("/api/clip-projects/{id}/render-jobs", projectId))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":(\\d+).*", "$1");
        fakeRender.awaitEntered();
        return Long.parseLong(id);
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

    static class FixedProbeService extends FfmpegProbeService {
        FixedProbeService(AppProperties appProperties) {
            super(appProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public VideoProbeResult probe(Path source) {
            return new VideoProbeResult(1920, 1080, 6000, "30/1", true);
        }
    }

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

    static class FakeRenderService extends FfmpegRenderService {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        FakeRenderService(AppProperties appProperties) {
            super(appProperties);
        }

        @Override
        public void execute(long jobId, Path workDir, long durationMs, FfmpegCommand command,
                            Path finalOutput, IntConsumer progressCallback) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
    }
}
