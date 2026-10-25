package com.scoresync.web;

import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreboardTemplate;
import com.scoresync.domain.VideoOriginType;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.RenderJobRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.repository.VideoSourceRepository;
import com.scoresync.service.ProjectService;
import com.scoresync.service.ScoreEventService;
import com.scoresync.service.ScoreReplayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 得分事件批量平移接口的手工装配集成测试
 */
class ScoreEventApiIntegrationTest {

    private MockMvc mockMvc;
    private JdbcTemplate jdbc;
    private Path dbFile;
    private VideoSourceRepository sources;
    private VideoAssetRepository assets;
    private MatchProjectRepository projects;
    private ScoreEventRepository events;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("test-shift-api-db", ".db");
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

        ScoreReplayService replayService = new ScoreReplayService();
        ProjectService projectService = new ProjectService(projects, assets, events,
                new RenderJobRepository(jdbc), replayService);
        ScoreEventService scoreEventService = new ScoreEventService(projects, assets, events,
                replayService, projectService);

        mockMvc = MockMvcBuilders.standaloneSetup(new ScoreEventController(scoreEventService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private long createProject(long durationMs) {
        long sourceId = sources.insert("/videos").id();
        long videoId = assets.insert(sourceId, "/videos/test.mp4", "测试视频",
                1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);
        return projects.insert("决赛", videoId, durationMs, "甲", "乙", 2,
                ScoreboardTemplate.CLASSIC, PlayerSide.A);
    }

    @Test
    void shiftEvents_returnsUpdatedEditorState() throws Exception {
        long projectId = createProject(60_000L);
        events.insert(projectId, 1_000L, PlayerSide.A);
        events.insert(projectId, 2_000L, PlayerSide.B);

        mockMvc.perform(patch("/api/projects/" + projectId + "/events/time-shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deltaMs\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].videoTimeMs").value(1_100))
                .andExpect(jsonPath("$.events[1].videoTimeMs").value(2_100));

        assertThat(storedTimes(projectId)).containsExactly(1_100L, 2_100L);
    }

    @Test
    void shiftEvents_withUnsupportedStep_returnsBadRequestWithChineseMessage() throws Exception {
        long projectId = createProject(60_000L);
        events.insert(projectId, 1_000L, PlayerSide.A);

        mockMvc.perform(patch("/api/projects/" + projectId + "/events/time-shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deltaMs\":250}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value("平移步长只支持 +100ms 或 -100ms"));

        assertThat(storedTimes(projectId)).containsExactly(1_000L);
    }

    @Test
    void shiftEvents_outOfRange_returnsBadRequestAndKeepsOriginalTimes() throws Exception {
        long projectId = createProject(60_000L);
        events.insert(projectId, 50L, PlayerSide.A);
        events.insert(projectId, 1_000L, PlayerSide.B);

        mockMvc.perform(patch("/api/projects/" + projectId + "/events/time-shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deltaMs\":-100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOMAIN_VALIDATION"))
                .andExpect(jsonPath("$.message").value("平移后得分时间超出视频时长范围，本次平移已取消"));

        assertThat(storedTimes(projectId)).containsExactly(50L, 1_000L);
    }

    @Test
    void shiftEvents_forUnknownProject_returnsNotFound() throws Exception {
        mockMvc.perform(patch("/api/projects/9999/events/time-shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deltaMs\":100}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void shiftEvents_withoutAnyEvent_returnsEmptyStateAndWritesNothing() throws Exception {
        long projectId = createProject(60_000L);

        mockMvc.perform(patch("/api/projects/" + projectId + "/events/time-shift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deltaMs\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0));

        assertThat(storedTimes(projectId)).isEmpty();
    }

    private java.util.List<Long> storedTimes(long projectId) {
        return jdbc.queryForList(
                "SELECT video_time_ms FROM score_event WHERE match_id = ? ORDER BY sequence_no ASC",
                Long.class, projectId);
    }
}