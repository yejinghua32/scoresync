package com.scoresync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scoresync.domain.EditorState;
import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.VideoOriginType;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.DomainValidationException;
import com.scoresync.web.dto.BackupDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupServiceTest {

    private JdbcTemplate jdbc;
    private Path dbFile;
    private VideoAssetRepository videoAssetRepository;
    private MatchProjectRepository projectRepository;
    private ScoreEventRepository eventRepository;
    private ScoreReplayService replayService;
    private ProjectService projectService;
    private BackupService backupService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("test-db", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE video_source (" +
                "  id INTEGER PRIMARY KEY, root_path TEXT NOT NULL UNIQUE," +
                "  enabled INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL" +
                ")");
        jdbc.execute("CREATE TABLE video_asset (" +
                "  id INTEGER PRIMARY KEY, source_id INTEGER NULL REFERENCES video_source(id)," +
                "  absolute_path TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL," +
                "  file_size INTEGER NOT NULL, modified_at INTEGER NOT NULL," +
                "  origin_type TEXT NOT NULL CHECK (origin_type IN ('SCANNED','PICKED'))," +
                "  created_at TEXT NOT NULL, updated_at TEXT NOT NULL" +
                ")");
        jdbc.execute("CREATE TABLE match_project (" +
                "  id INTEGER PRIMARY KEY, name TEXT NOT NULL," +
                "  video_asset_id INTEGER NOT NULL REFERENCES video_asset(id), duration_ms INTEGER NOT NULL CHECK (duration_ms > 0)," +
                "  player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL CHECK (target_wins >= 1)," +
                "  scoreboard_template TEXT NOT NULL DEFAULT 'CLASSIC'," +
                "  first_server TEXT NOT NULL DEFAULT 'A' CHECK (first_server IN ('A','B'))," +
                "  created_at TEXT NOT NULL, updated_at TEXT NOT NULL" +
                ")");
        jdbc.execute("CREATE TABLE score_event (" +
                "  id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL REFERENCES match_project(id) ON DELETE CASCADE," +
                "  video_time_ms INTEGER NOT NULL CHECK (video_time_ms >= 0)," +
                "  player_side TEXT NOT NULL CHECK (player_side IN ('A','B')), sequence_no INTEGER NOT NULL," +
                "  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE (match_id, sequence_no)" +
                ")");
        jdbc.execute("CREATE INDEX idx_score_event_match_timeline ON score_event(match_id, video_time_ms, sequence_no)");
        jdbc.execute("CREATE TABLE render_job (" +
                "  id INTEGER PRIMARY KEY, match_id INTEGER NOT NULL," +
                "  template_code TEXT NOT NULL, status TEXT NOT NULL," +
                "  progress_percent INTEGER NOT NULL DEFAULT 0," +
                "  output_path TEXT NULL, failure_message TEXT NULL," +
                "  created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL" +
                ")");

        videoAssetRepository = new VideoAssetRepository(jdbc);
        projectRepository = new MatchProjectRepository(jdbc);
        eventRepository = new ScoreEventRepository(jdbc);
        replayService = new ScoreReplayService();
        projectService = new ProjectService(projectRepository, videoAssetRepository, eventRepository,
                new com.scoresync.repository.RenderJobRepository(jdbc), replayService);
        backupService = new BackupService(projectRepository, videoAssetRepository, eventRepository, replayService, projectService);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private long createProjectWithEvents(int targetWins, List<Long> videoTimesMs, List<PlayerSide> sides) {
        long assetId = videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, System.currentTimeMillis(), VideoOriginType.PICKED);
        EditorState state = projectService.create("测试比赛", assetId, 120_000L, "选手A", "选手B", targetWins);
        long projectId = state.project().id();

        ScoreEventService events = new ScoreEventService(projectRepository, videoAssetRepository, eventRepository, replayService, projectService);
        for (int i = 0; i < videoTimesMs.size(); i++) {
            events.add(projectId, videoTimesMs.get(i), sides.get(i));
        }
        return projectId;
    }

    @Test
    void exportDoesNotContainAbsolutePathOrRootPath() throws Exception {
        long projectId = createProjectWithEvents(3, List.of(1000L), List.of(PlayerSide.A));

        BackupDocument backup = backupService.exportProject(projectId);

        String json = objectMapper.writeValueAsString(backup);
        assertThat(json).doesNotContain("absolutePath", "rootPath");
        assertThat(json).doesNotContain("\"id\"");
    }

    @Test
    void exportPreservesProjectData() {
        long projectId = createProjectWithEvents(3, List.of(1000L, 2000L), List.of(PlayerSide.A, PlayerSide.B));

        BackupDocument backup = backupService.exportProject(projectId);

        assertThat(backup.project().name()).isEqualTo("测试比赛");
        assertThat(backup.project().playerA()).isEqualTo("选手A");
        assertThat(backup.project().playerB()).isEqualTo("选手B");
        assertThat(backup.project().targetWins()).isEqualTo(3);
    }

    @Test
    void exportEventsOrderedByVideoTimeMsAndSequenceNo() {
        long projectId = createProjectWithEvents(3,
                List.of(1000L, 1000L, 2000L, 3000L),
                List.of(PlayerSide.A, PlayerSide.B, PlayerSide.A, PlayerSide.B));

        BackupDocument backup = backupService.exportProject(projectId);

        assertThat(backup.events()).hasSize(4);
        assertThat(backup.events().get(0).videoTimeMs()).isEqualTo(1000L);
        assertThat(backup.events().get(0).sequenceNo()).isEqualTo(1L);
        assertThat(backup.events().get(1).videoTimeMs()).isEqualTo(1000L);
        assertThat(backup.events().get(1).sequenceNo()).isEqualTo(2L);
        assertThat(backup.events().get(2).videoTimeMs()).isEqualTo(2000L);
        assertThat(backup.events().get(3).videoTimeMs()).isEqualTo(3000L);
    }

    @Test
    void importToEmptyDbRestoresProjectCorrectly() {
        long modifiedAt = System.currentTimeMillis();
        long assetId = videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, modifiedAt, VideoOriginType.PICKED);

        BackupDocument.BackupVideo video = new BackupDocument.BackupVideo("测试视频", 1024L, modifiedAt, "PICKED");
        BackupDocument.BackupProject project = new BackupDocument.BackupProject("恢复的比赛", 120_000L, "选手X", "选手Y", 3, "A");
        List<BackupDocument.BackupEvent> events = List.of(
                new BackupDocument.BackupEvent(1000L, "A", 1),
                new BackupDocument.BackupEvent(2000L, "B", 2)
        );
        BackupDocument backup = new BackupDocument(1, project, video, events);

        EditorState state = backupService.importDocument(backup);

        assertThat(state.project().name()).isEqualTo("恢复的比赛");
        assertThat(state.project().playerA()).isEqualTo("选手X");
        assertThat(state.project().playerB()).isEqualTo("选手Y");
        assertThat(state.project().targetWins()).isEqualTo(3);
        assertThat(state.events()).hasSize(2);
        assertThat(state.events().get(0).playerSide()).isEqualTo(PlayerSide.A);
        assertThat(state.events().get(1).playerSide()).isEqualTo(PlayerSide.B);
    }

    @Test
    void importWithInvalidSchemaVersionThrowsException() {
        long assetId = videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, System.currentTimeMillis(), VideoOriginType.PICKED);

        BackupDocument.BackupVideo video = new BackupDocument.BackupVideo("测试视频", 1024L, assetId, "PICKED");
        BackupDocument.BackupProject project = new BackupDocument.BackupProject("恢复的比赛", 120_000L, "选手X", "选手Y", 3, "A");
        List<BackupDocument.BackupEvent> events = List.of();
        BackupDocument backup = new BackupDocument(999, project, video, events);

        assertThatThrownBy(() -> backupService.importDocument(backup))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("不支持的备份格式版本");
    }

    @Test
    void importWithMismatchedVideoThrowsException() {
        videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, System.currentTimeMillis(), VideoOriginType.PICKED);

        BackupDocument.BackupVideo video = new BackupDocument.BackupVideo("不存在的视频", 9999L, 0L, "PICKED");
        BackupDocument.BackupProject project = new BackupDocument.BackupProject("恢复的比赛", 120_000L, "选手X", "选手Y", 3, "A");
        List<BackupDocument.BackupEvent> events = List.of();
        BackupDocument backup = new BackupDocument(1, project, video, events);

        assertThatThrownBy(() -> backupService.importDocument(backup))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("未找到与备份匹配的视频");
    }

    @Test
    void importWithMultipleMatchingVideosThrowsException() {
        long now = System.currentTimeMillis();
        videoAssetRepository.insert(null, "/videos/test1.mp4", "测试视频",
                1024L, now, VideoOriginType.PICKED);
        videoAssetRepository.insert(null, "/videos/test2.mp4", "测试视频",
                1024L, now, VideoOriginType.PICKED);

        BackupDocument.BackupVideo video = new BackupDocument.BackupVideo("测试视频", 1024L, now, "PICKED");
        BackupDocument.BackupProject project = new BackupDocument.BackupProject("恢复的比赛", 120_000L, "选手X", "选手Y", 3, "A");
        List<BackupDocument.BackupEvent> events = List.of();
        BackupDocument backup = new BackupDocument(1, project, video, events);

        assertThatThrownBy(() -> backupService.importDocument(backup))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("找到多个与备份匹配的视频");
    }

    @Test
    void importDoesNotLeavePartialProjectOnFailure() {
        long now = System.currentTimeMillis();
        videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, now, VideoOriginType.PICKED);

        BackupDocument.BackupVideo video = new BackupDocument.BackupVideo("测试视频", 1024L, now, "PICKED");
        BackupDocument.BackupProject project = new BackupDocument.BackupProject("恢复的比赛", 120_000L, "选手X", "选手Y", 3, "A");
        List<BackupDocument.BackupEvent> events = List.of();
        BackupDocument invalidBackup = new BackupDocument(999, project, video, events);

        int initialCount = projectRepository.findAll().size();

        assertThatThrownBy(() -> backupService.importDocument(invalidBackup))
                .isInstanceOf(DomainValidationException.class);

        assertThat(projectRepository.findAll()).hasSize(initialCount);
    }
}
