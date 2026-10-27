package com.scoresync.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    @Test
    void createsClipTablesOnLegacyDatabase() throws Exception {
        Path dbFile = Files.createTempFile("migration-clip-test", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE video_asset (id INTEGER PRIMARY KEY, source_id INTEGER NULL, "
                + "absolute_path TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, file_size INTEGER NOT NULL, "
                + "modified_at INTEGER NOT NULL, origin_type TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");

        new SchemaMigrationRunner(jdbc, dataSource).run();

        assertThat(columnsOf(jdbc, "clip_project")).contains(
                "id", "name", "video_asset_id", "large_step", "small_step",
                "source_file_size", "source_modified_at", "total_frames", "frame_index_status");
        assertThat(columnsOf(jdbc, "clip_segment")).contains(
                "id", "project_id", "segment_type", "start_frame", "end_frame");
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        Path dbFile = Files.createTempFile("migration-idempotent-test", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        new SchemaMigrationRunner(jdbc, dataSource).run();
        new SchemaMigrationRunner(jdbc, dataSource).run();

        assertThat(columnsOf(jdbc, "clip_project")).contains("large_step", "small_step");
    }

    @Test
    void schemaSqlCreatesSameClipColumnsAsMigration() throws Exception {
        Path dbFile = Files.createTempFile("schema-sql-test", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        applySchemaSql(jdbc);

        assertThat(columnsOf(jdbc, "clip_project")).contains(
                "id", "name", "video_asset_id", "large_step", "small_step",
                "source_file_size", "source_modified_at", "total_frames", "frame_index_status");
        assertThat(columnsOf(jdbc, "clip_segment")).contains(
                "id", "project_id", "segment_type", "start_frame", "end_frame");
    }

    private List<String> columnsOf(JdbcTemplate jdbc, String table) {
        return jdbc.query("PRAGMA table_info(" + table + ")", (rs, rowNum) -> rs.getString("name"));
    }

    /**
     * 按 schema.sql 的内容建库，验证全新安装也能得到剪辑相关表
     */
    private void applySchemaSql(JdbcTemplate jdbc) throws Exception {
        String sql;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("schema.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                jdbc.execute(trimmed);
            }
        }
    }
}
