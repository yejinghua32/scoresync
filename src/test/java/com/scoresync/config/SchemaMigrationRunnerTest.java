package com.scoresync.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationRunnerTest {

    @Test
    void addsClassicTemplateColumnToLegacyProjectTable() throws Exception {
        Path dbFile = Files.createTempFile("migration-test", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE match_project (id INTEGER PRIMARY KEY, name TEXT NOT NULL, video_asset_id INTEGER NOT NULL, duration_ms INTEGER NOT NULL, player_a TEXT NOT NULL, player_b TEXT NOT NULL, target_wins INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");

        new SchemaMigrationRunner(jdbc, dataSource).run();

        List<String> columns = jdbc.query("PRAGMA table_info(match_project)",
                (rs, rowNum) -> rs.getString("name"));
        assertThat(columns).contains("scoreboard_template", "first_server");
        jdbc.update("INSERT INTO match_project (id, name, video_asset_id, duration_ms, player_a, player_b, target_wins, created_at, updated_at) VALUES (1, 'test', 1, 1000, 'A', 'B', 1, '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        assertThat(jdbc.queryForObject("SELECT scoreboard_template FROM match_project WHERE id = 1", String.class))
                .isEqualTo("CLASSIC");
        assertThat(jdbc.queryForObject("SELECT first_server FROM match_project WHERE id = 1", String.class))
                .isEqualTo("A");
    }
}
