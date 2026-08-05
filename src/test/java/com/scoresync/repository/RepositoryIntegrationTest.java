package com.scoresync.repository;

import com.scoresync.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryIntegrationTest {

    private JdbcTemplate jdbc;
    private VideoSourceRepository sources;
    private VideoAssetRepository assets;
    private MatchProjectRepository projects;
    private ScoreEventRepository events;

    @BeforeEach
    void setUp() throws Exception {
        Path dbFile = Files.createTempFile("test-db", ".db");
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
        sources = new VideoSourceRepository(jdbc);
        assets = new VideoAssetRepository(jdbc);
        projects = new MatchProjectRepository(jdbc);
        events = new ScoreEventRepository(jdbc);
    }

    @Test
    void findsEventsInTimelineThenSequenceOrder() {
        VideoSource source = sources.insert("/videos");
        long sourceId = source.id();
        long assetId = assets.insert(sourceId, "/videos/match.mp4", "决赛", 1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);
        MatchProject project = projects.findById(projects.insert("决赛", assetId, 120_000L, "甲", "乙", 3, ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A));
        events.insert(project.id(), 10_000L, PlayerSide.B);
        events.insert(project.id(), 10_000L, PlayerSide.A);

        assertThat(events.findByMatchId(project.id()))
                .extracting(ScoreEvent::playerSide)
                .containsExactly(PlayerSide.B, PlayerSide.A);
    }

    @Test
    void findAllPaged_returnsDescendingByIdAndRespectsLimitOffset() {
        VideoSource source = sources.insert("/videos");
        long assetId = assets.insert(source.id(), "/videos/match.mp4", "决赛", 1024L, System.currentTimeMillis(), VideoOriginType.SCANNED);
        long firstId = -1;
        long lastId = -1;
        for (int i = 0; i < 5; i++) {
            long id = projects.insert("项目" + i, assetId, 60_000L, "甲", "乙", 2, ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A);
            if (i == 0) firstId = id;
            if (i == 4) lastId = id;
        }

        assertThat(projects.count()).isEqualTo(5);

        List<MatchProject> firstPage = projects.findAll(0, 2);
        assertThat(firstPage).hasSize(2);
        assertThat(firstPage.get(0).id()).isEqualTo(lastId);
        assertThat(firstPage.get(1).id()).isEqualTo(lastId - 1);

        List<MatchProject> secondPage = projects.findAll(2, 2);
        assertThat(secondPage).hasSize(2);
        assertThat(secondPage.get(0).id()).isEqualTo(lastId - 2);

        List<MatchProject> lastPartial = projects.findAll(4, 2);
        assertThat(lastPartial).hasSize(1);
        assertThat(lastPartial.get(0).id()).isEqualTo(firstId);

        assertThat(projects.findAll(10, 2)).isEmpty();
    }
}
