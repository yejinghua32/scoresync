package com.scoresync.service;

import com.scoresync.domain.EditorState;
import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.VideoOriginType;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.DomainValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoreEventServiceTest {

    private JdbcTemplate jdbc;
    private Path dbFile;
    private VideoAssetRepository videoAssetRepository;
    private MatchProjectRepository projectRepository;
    private ScoreEventRepository eventRepository;
    private ScoreReplayService replayService;
    private ProjectService projectService;
    private ScoreEventService events;

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
        events = new ScoreEventService(projectRepository, videoAssetRepository, eventRepository, replayService, projectService);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dbFile != null) {
            Files.deleteIfExists(dbFile);
        }
    }

    private long createProject(int targetWins) {
        long assetId = videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, System.currentTimeMillis(), VideoOriginType.PICKED);
        EditorState state = projectService.create("测试比赛", assetId, 120_000L, "选手A", "选手B", targetWins);
        return state.project().id();
    }

    private void insertCompleteTwelveToTen(long projectId, PlayerSide winner) {
        PlayerSide loser = winner == PlayerSide.A ? PlayerSide.B : PlayerSide.A;
        long timeMs = 0;
        for (int i = 0; i < 10; i++) {
            events.add(projectId, timeMs++, winner);
        }
        for (int i = 0; i < 10; i++) {
            events.add(projectId, timeMs++, loser);
        }
        events.add(projectId, timeMs++, winner);
        events.add(projectId, timeMs++, winner);
    }

    @Test
    void createProjectWithValidData() {
        long assetId = videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, System.currentTimeMillis(), VideoOriginType.PICKED);
        EditorState state = projectService.create("决赛", assetId, 120_000L, "甲", "乙", 3);

        assertThat(state.project().name()).isEqualTo("决赛");
        assertThat(state.project().playerA()).isEqualTo("甲");
        assertThat(state.project().playerB()).isEqualTo("乙");
        assertThat(state.project().targetWins()).isEqualTo(3);
        assertThat(state.video().displayName()).isEqualTo("测试视频");
    }

    @Test
    void createProjectRejectsBlankName() {
        long assetId = videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, System.currentTimeMillis(), VideoOriginType.PICKED);
        assertThatThrownBy(() -> projectService.create("", assetId, 120_000L, "甲", "乙", 3))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("项目名称不能为空");
    }

    @Test
    void createProjectRejectsInvalidTargetWins() {
        long assetId = videoAssetRepository.insert(null, "/videos/test.mp4", "测试视频",
                1024L, System.currentTimeMillis(), VideoOriginType.PICKED);
        assertThatThrownBy(() -> projectService.create("决赛", assetId, 120_000L, "甲", "乙", 0))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void addEventToProject() {
        long projectId = createProject(3);
        EditorState state = events.add(projectId, 1_000, PlayerSide.A);

        assertThat(state.events()).hasSize(1);
        assertThat(state.events().get(0).playerSide()).isEqualTo(PlayerSide.A);
        assertThat(state.events().get(0).scoreA()).isEqualTo(1);
    }

    @Test
    void addEventRejectsTimeOutOfRange() {
        long projectId = createProject(3);
        assertThatThrownBy(() -> events.add(projectId, 200_000, PlayerSide.A))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("得分时间必须位于视频时长范围内");
    }

    @Test
    void rejectsInvalidCandidateWithoutPersistingIt() {
        long projectId = createProject(1);
        insertCompleteTwelveToTen(projectId, PlayerSide.A);
        assertThatThrownBy(() -> events.add(projectId, 30_000, PlayerSide.B))
                .isInstanceOf(DomainValidationException.class);
        assertThat(eventRepository.findByMatchId(projectId)).hasSize(22);
    }

    @Test
    void deleteReplaysAllLaterDerivedState() {
        long projectId = createProject(2);
        long a = events.add(projectId, 1_000, PlayerSide.A).events().get(0).eventId();
        events.add(projectId, 2_000, PlayerSide.B);
        EditorState state = events.delete(a);

        assertThat(state.events()).singleElement().satisfies(event -> {
            assertThat(event.playerSide()).isEqualTo(PlayerSide.B);
            assertThat(event.scoreA()).isZero();
            assertThat(event.scoreB()).isEqualTo(1);
        });
    }

    @Test
    void updateEventChangesPlayerSide() {
        long projectId = createProject(2);
        long eventId = events.add(projectId, 1_000, PlayerSide.A).events().get(0).eventId();
        EditorState state = events.update(eventId, 1_000, PlayerSide.B);

        assertThat(state.events()).singleElement();
        assertThat(state.events().get(0).playerSide()).isEqualTo(PlayerSide.B);
        assertThat(state.events().get(0).scoreB()).isEqualTo(1);
    }

    @Test
    void updateEventRejectsTimeOutOfRange() {
        long projectId = createProject(2);
        long eventId = events.add(projectId, 1_000, PlayerSide.A).events().get(0).eventId();
        assertThatThrownBy(() -> events.update(eventId, -1, PlayerSide.A))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void deleteRemovesEventFromDatabase() {
        long projectId = createProject(2);
        long eventId = events.add(projectId, 1_000, PlayerSide.A).events().get(0).eventId();
        events.add(projectId, 2_000, PlayerSide.B);

        events.delete(eventId);

        assertThat(eventRepository.findByMatchId(projectId)).hasSize(1);
        assertThat(eventRepository.findByMatchId(projectId).get(0).playerSide()).isEqualTo(PlayerSide.B);
    }

    @Test
    void undoLastRemovesMostRecentlyAddedEvent() {
        long projectId = createProject(2);
        events.add(projectId, 1_000, PlayerSide.A);
        events.add(projectId, 2_000, PlayerSide.B);
        events.add(projectId, 3_000, PlayerSide.A);

        EditorState state = events.undoLast(projectId);

        assertThat(state.events()).hasSize(2);
        assertThat(eventRepository.findByMatchId(projectId)).hasSize(2);
        assertThat(eventRepository.findMostRecentByMatchId(projectId))
                .hasValueSatisfying(event -> assertThat(event.playerSide()).isEqualTo(PlayerSide.B));
    }

    @Test
    void undoLastCanBeRepeatedUntilNoEventsRemain() {
        long projectId = createProject(2);
        events.add(projectId, 1_000, PlayerSide.A);
        events.add(projectId, 2_000, PlayerSide.B);

        events.undoLast(projectId);
        events.undoLast(projectId);

        assertThat(eventRepository.findByMatchId(projectId)).isEmpty();
    }

    @Test
    void undoLastRejectsWhenNoEventsExist() {
        long projectId = createProject(2);
        assertThatThrownBy(() -> events.undoLast(projectId))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("没有可撤销的得分事件");
    }

    @Test
    void editorStateReturnsCorrectProjectState() {
        long projectId = createProject(2);
        events.add(projectId, 1_000, PlayerSide.A);
        events.add(projectId, 2_000, PlayerSide.B);

        EditorState state = projectService.editorState(projectId);

        assertThat(state.project().id()).isEqualTo(projectId);
        assertThat(state.events()).hasSize(2);
        assertThat(state.match().currentScoreA()).isEqualTo(1);
        assertThat(state.match().currentScoreB()).isEqualTo(1);
    }
}
