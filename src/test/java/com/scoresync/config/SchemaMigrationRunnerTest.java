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
        assertThat(columns).contains("scoreboard_template", "first_server", "show_project_name");
        jdbc.update("INSERT INTO match_project (id, name, video_asset_id, duration_ms, player_a, player_b, target_wins, created_at, updated_at) VALUES (1, 'test', 1, 1000, 'A', 'B', 1, '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z')");
        assertThat(jdbc.queryForObject("SELECT scoreboard_template FROM match_project WHERE id = 1", String.class))
                .isEqualTo("CLASSIC");
        assertThat(jdbc.queryForObject("SELECT first_server FROM match_project WHERE id = 1", String.class))
                .isEqualTo("A");
        // 旧项目升级后默认不显示项目名称
        assertThat(jdbc.queryForObject("SELECT show_project_name FROM match_project WHERE id = 1", Integer.class))
                .isZero();
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
                "id", "project_id", "segment_type", "start_frame", "end_frame", "featured");
    }

    @Test
    void addsFeaturedColumnToLegacyClipSegmentTable() throws Exception {
        Path dbFile = Files.createTempFile("migration-clip-featured-test", ".db");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + dbFile);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE clip_project (id INTEGER PRIMARY KEY, name TEXT NOT NULL, "
                + "video_asset_id INTEGER NOT NULL, large_step INTEGER NOT NULL DEFAULT 10, "
                + "small_step INTEGER NOT NULL DEFAULT 5, source_file_size INTEGER NOT NULL, "
                + "source_modified_at INTEGER NOT NULL, total_frames INTEGER NULL, "
                + "frame_index_status TEXT NOT NULL DEFAULT 'PENDING', "
                + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE clip_segment ("
                + "id INTEGER PRIMARY KEY, "
                + "project_id INTEGER NOT NULL REFERENCES clip_project(id) ON DELETE CASCADE, "
                + "segment_type TEXT NOT NULL CHECK (segment_type IN ('KEEP','DROP')), "
                + "start_frame INTEGER NOT NULL CHECK (start_frame >= 0), "
                + "end_frame INTEGER NOT NULL CHECK (end_frame > start_frame), "
                + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        jdbc.update("INSERT INTO clip_segment (id, project_id, segment_type, start_frame, end_frame, "
                + "created_at, updated_at) VALUES (1, 1, 'KEEP', 0, 10, '2026-08-04T00:00:00Z', "
                + "'2026-08-04T00:00:00Z')");

        new SchemaMigrationRunner(jdbc, dataSource).run();

        assertThat(columnsOf(jdbc, "clip_segment")).contains("featured");
        // 旧库已有片段升级后保持类型与范围，精选默认为假
        assertThat(jdbc.queryForObject("SELECT segment_type FROM clip_segment WHERE id = 1", String.class))
                .isEqualTo("KEEP");
        assertThat(jdbc.queryForObject("SELECT start_frame FROM clip_segment WHERE id = 1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT end_frame FROM clip_segment WHERE id = 1", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT featured FROM clip_segment WHERE id = 1", Integer.class)).isZero();

        // 迁移重复执行不会再次添加列
        new SchemaMigrationRunner(jdbc, dataSource).run();
        assertThat(columnsOf(jdbc, "clip_segment")).contains("featured");
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
                "id", "project_id", "segment_type", "start_frame", "end_frame", "featured");
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
