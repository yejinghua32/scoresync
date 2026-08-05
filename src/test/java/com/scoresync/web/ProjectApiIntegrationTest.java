package com.scoresync.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scoresync.domain.*;
import com.scoresync.repository.*;
import com.scoresync.service.BackupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectApiIntegrationTest {

    private MockMvc mockMvc;
    private VideoSourceRepository sources;
    private VideoAssetRepository assets;
    private MatchProjectRepository projects;
    private ScoreEventRepository events;
    private RenderJobRepository renderJobs;
    private BackupService backupService;
    private ObjectMapper objectMapper;
    private JdbcTemplate jdbc;
    private Path dbFile;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("test-api-db", ".db");
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
                CREATE TABLE match_project (
                  id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                  video_asset_id INTEGER NOT NULL REFERENCES video_asset(id), duration_ms INTEGER NOT NULL CHECK (duration_ms > 0),
                  player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL CHECK (target_wins >= 1),
                  scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC',
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
        events = new ScoreEventRepository(jdbc);
        renderJobs = new RenderJobRepository(jdbc);

        com.scoresync.service.ScoreReplayService replayService = new com.scoresync.service.ScoreReplayService();
        com.scoresync.service.ProjectService projectService = new com.scoresync.service.ProjectService(projects, assets, events, renderJobs, replayService);
        com.scoresync.service.ScoreEventService scoreEventService = new com.scoresync.service.ScoreEventService(projects, assets, events, replayService, projectService);
        com.scoresync.service.VideoLibraryService videoLibraryService = new com.scoresync.service.VideoLibraryService(sources, assets);
        com.scoresync.service.MediaStreamingService mediaStreamingService = new com.scoresync.service.MediaStreamingService(assets);
        com.scoresync.service.NativeFilePickerService filePickerService = new com.scoresync.service.AwtNativeFilePickerService();

        VideoSourceController videoSourceController = new VideoSourceController(videoLibraryService, filePickerService);
        ProjectController projectController = new ProjectController(projectService);
        ScoreEventController scoreEventController = new ScoreEventController(scoreEventService);
        MediaController mediaController = new MediaController(mediaStreamingService, assets);
        backupService = new BackupService(projects, assets, events, replayService, projectService);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BackupController backupController = new BackupController(backupService, objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(videoSourceController, projectController, scoreEventController, mediaController, backupController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void createProject_shouldReturnCreated() throws Exception {
        VideoSource source = sources.insert("/videos");
        long sourceId = source.id();
        long videoId = assets.insert(sourceId, "/videos/test.mp4", "测试视频", 1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"半决赛\",\"videoId\":" + videoId + ",\"durationMs\":60000,\"playerA\":\"甲\",\"playerB\":\"乙\",\"targetWins\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.project.targetWins").value(2));
    }

    @Test
    void addEvent_outsideVideoRange_shouldReturnBadRequest() throws Exception {
        VideoSource source = sources.insert("/videos");
        long sourceId = source.id();
        long videoId = assets.insert(sourceId, "/videos/test.mp4", "测试视频", 1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);
        long projectId = projects.insert("决赛", videoId, 60000L, "甲", "乙", 2, ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A);

        mockMvc.perform(post("/api/projects/" + projectId + "/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoTimeMs\":60001,\"playerSide\":\"A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"));
    }

    @Test
    void streamMedia_withRangeHeader_shouldReturnPartialContent() throws Exception {
        Path videoFile = Files.createTempFile("test-video", ".mp4");
        Files.writeString(videoFile, "0123456789");
        VideoSource source = sources.insert(videoFile.getParent().toString());
        long videoId = assets.insert(source.id(), videoFile.toString(), "测试视频", 10L, System.currentTimeMillis(), VideoOriginType.SCANNED);

        mockMvc.perform(get("/api/media/" + videoId)
                        .header("Range", "bytes=0-1"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 0-1/10"));
    }

    @Test
    void streamMedia_nonExistentVideoId_shouldReturn404() throws Exception {
        mockMvc.perform(get("/api/media/99999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listProjects_returnsPaginatedResultsInDescendingOrder() throws Exception {
        VideoSource source = sources.insert("/videos");
        long videoId = assets.insert(source.id(), "/videos/test.mp4", "测试视频", 1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);
        for (int i = 0; i < 3; i++) {
            projects.insert("项目" + i, videoId, 60_000L, "甲" + i, "乙" + i, 2, ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A);
        }

        mockMvc.perform(get("/api/projects").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("项目2"))
                .andExpect(jsonPath("$.items[1].name").value("项目1"));

        mockMvc.perform(get("/api/projects").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("项目0"));
    }

    @Test
    void deleteProject_removesProjectCascadesEventsAndReturns204() throws Exception {
        VideoSource source = sources.insert("/videos");
        long videoId = assets.insert(source.id(), "/videos/test.mp4", "测试视频", 1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);
        long projectId = projects.insert("决赛", videoId, 60_000L, "甲", "乙", 2, ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A);
        events.insert(projectId, 1_000L, com.scoresync.domain.PlayerSide.A);
        events.insert(projectId, 2_000L, com.scoresync.domain.PlayerSide.B);
        long jobId = renderJobs.insert(projectId, ScoreboardTemplate.CLASSIC);
        RenderJob queued = renderJobs.findById(jobId);
        renderJobs.update(new RenderJob(
                queued.id(), queued.matchId(), queued.templateCode(), RenderJobStatus.FAILED,
                queued.progressPercent(), queued.outputPath(), "render cancelled earlier",
                queued.createdAt(), queued.startedAt(), queued.finishedAt()));

        mockMvc.perform(delete("/api/projects/{id}", projectId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/projects/{id}/editor-state", projectId))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM score_event WHERE match_id = ?",
                Integer.class, projectId)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM render_job WHERE match_id = ?",
                Integer.class, projectId)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM match_project WHERE id = ?",
                Integer.class, projectId)).isEqualTo(0);
    }

    @Test
    void deleteProject_nonExistent_returns404() throws Exception {
        mockMvc.perform(delete("/api/projects/{id}", 99999))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteProject_withActiveRenderJob_returns409() throws Exception {
        VideoSource source = sources.insert("/videos");
        long videoId = assets.insert(source.id(), "/videos/test.mp4", "测试视频", 1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);
        long projectId = projects.insert("决赛", videoId, 60_000L, "甲", "乙", 2, ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A);
        long jobId = renderJobs.insert(projectId, ScoreboardTemplate.CLASSIC);
        RenderJob job = renderJobs.findById(jobId);
        renderJobs.update(new com.scoresync.domain.RenderJob(
                job.id(), job.matchId(), job.templateCode(), RenderJobStatus.RUNNING,
                job.progressPercent(), job.outputPath(), job.failureMessage(),
                job.createdAt(), java.time.Instant.now(), job.finishedAt()));

        mockMvc.perform(delete("/api/projects/{id}", projectId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RENDER_JOB_ACTIVE"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM match_project WHERE id = ?",
                Integer.class, projectId)).isEqualTo(1);
    }

    @Test
    void bestOfFiveExportClearReRegisterImport_retainsEquivalentState() throws Exception {
        VideoSource source = sources.insert("/videos");
        long sourceId = source.id();
        long modifiedAt = System.currentTimeMillis();
        long videoId = assets.insert(sourceId, "/videos/test.mp4", "测试视频", 1024L, modifiedAt, VideoOriginType.PICKED);

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"决赛\",\"videoId\":" + videoId + ",\"durationMs\":120000,\"playerA\":\"甲\",\"playerB\":\"乙\",\"targetWins\":3}"))
                .andExpect(status().isCreated());

        long projectId = 1;

        List<Long> videoTimes = new ArrayList<>();
        List<String> sides = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            videoTimes.add((long) (1000 + i * 1000));
            sides.add("A");
        }
        for (int i = 0; i < 11; i++) {
            videoTimes.add((long) (12000 + i * 1000));
            sides.add("B");
        }
        for (int i = 0; i < 11; i++) {
            videoTimes.add((long) (24000 + i * 1000));
            sides.add("A");
        }
        for (int i = 0; i < 11; i++) {
            videoTimes.add((long) (36000 + i * 1000));
            sides.add("A");
        }

        for (int i = 0; i < videoTimes.size(); i++) {
            mockMvc.perform(post("/api/projects/" + projectId + "/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"videoTimeMs\":" + videoTimes.get(i) + ",\"playerSide\":\"" + sides.get(i) + "\"}"))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/projects/{id}/editor-state", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.match.matchCompleted").value(true))
                .andExpect(jsonPath("$.match.setWinsA").value(3))
                .andExpect(jsonPath("$.match.setWinsB").value(1));

        byte[] backupBytes = mockMvc.perform(get("/api/projects/{id}/backup", projectId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String backupJson = new String(backupBytes, java.nio.charset.StandardCharsets.UTF_8);

        Files.deleteIfExists(dbFile);
        Path newDbFile = Files.createTempFile("test-api-db", ".db");
        DriverManagerDataSource newDataSource = new DriverManagerDataSource();
        newDataSource.setDriverClassName("org.sqlite.JDBC");
        newDataSource.setUrl("jdbc:sqlite:" + newDbFile);
        jdbc = new JdbcTemplate(newDataSource);
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
                  scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC',
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
        events = new ScoreEventRepository(jdbc);
        com.scoresync.service.ScoreReplayService replayService = new com.scoresync.service.ScoreReplayService();
        com.scoresync.service.ProjectService projectService = new com.scoresync.service.ProjectService(projects, assets, events, new RenderJobRepository(jdbc), replayService);
        com.scoresync.service.ScoreEventService scoreEventService = new com.scoresync.service.ScoreEventService(projects, assets, events, replayService, projectService);
        com.scoresync.service.VideoLibraryService videoLibraryService = new com.scoresync.service.VideoLibraryService(sources, assets);
        com.scoresync.service.MediaStreamingService mediaStreamingService = new com.scoresync.service.MediaStreamingService(assets);
        com.scoresync.service.NativeFilePickerService filePickerService = new com.scoresync.service.AwtNativeFilePickerService();
        backupService = new BackupService(projects, assets, events, replayService, projectService);

        VideoSourceController videoSourceController = new VideoSourceController(videoLibraryService, filePickerService);
        ProjectController projectController = new ProjectController(projectService);
        ScoreEventController scoreEventController = new ScoreEventController(scoreEventService);
        MediaController mediaController = new MediaController(mediaStreamingService, assets);
        BackupController backupController = new BackupController(backupService, objectMapper);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(videoSourceController, projectController, scoreEventController, mediaController, backupController)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(converter)
                .build();

        sources.insert("/videos");
        assets.insert(null, "/videos/test.mp4", "测试视频", 1024L, modifiedAt, VideoOriginType.PICKED);

        com.scoresync.web.dto.BackupDocument importedDoc = objectMapper.readValue(backupJson, com.scoresync.web.dto.BackupDocument.class);

        mockMvc.perform(post("/api/projects/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(importedDoc)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.events.length()").value(44));

        String stateJson = mockMvc.perform(get("/api/projects/1/editor-state"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        com.fasterxml.jackson.databind.ObjectMapper strictMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        strictMapper.registerModule(new JavaTimeModule());
        com.scoresync.domain.EditorState state = strictMapper.readValue(stateJson, com.scoresync.domain.EditorState.class);

        assertThat(state.events()).hasSize(44);
        for (int i = 0; i < 44; i++) {
            assertThat(state.events().get(i).videoTimeMs()).isEqualTo(videoTimes.get(i));
            assertThat(state.events().get(i).playerSide().name()).isEqualTo(sides.get(i));
        }
    }
}
