package com.scoresync.config;

/**
 * 数据库 Schema 迁移运行器
 * 应用启动时执行，通过 PRAGMA 检查并添加新增列（适配已有数据库）
 * 注意：需与 schema.sql 保持同步
 */

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;

@Component
public class SchemaMigrationRunner implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public SchemaMigrationRunner(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    /**
     * 执行数据库结构迁移
     */
    @Override
    public void run(String... args) {
        boolean projectTableExists = tableExists("match_project");
        if (projectTableExists) {
            boolean exists = jdbc.query("PRAGMA table_info(match_project)",
                            (rs, rowNum) -> rs.getString("name")).stream()
                    .anyMatch("scoreboard_template"::equals);
            if (!exists) {
                jdbc.execute("ALTER TABLE match_project ADD COLUMN scoreboard_template "
                        + "TEXT NOT NULL DEFAULT 'CLASSIC'");
            }
            boolean firstServerExists = jdbc.query("PRAGMA table_info(match_project)",
                            (rs, rowNum) -> rs.getString("name")).stream()
                    .anyMatch("first_server"::equals);
            if (!firstServerExists) {
                jdbc.execute("ALTER TABLE match_project ADD COLUMN first_server "
                        + "TEXT NOT NULL DEFAULT 'A'");
            }
        }
        boolean settingsTableExists = tableExists("app_settings");
        if (!settingsTableExists) {
            jdbc.execute("CREATE TABLE app_settings ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                    + "player_a_name TEXT NOT NULL DEFAULT '甲', "
                    + "player_b_name TEXT NOT NULL DEFAULT '乙')");
            jdbc.execute("INSERT INTO app_settings (id, player_a_name, player_b_name) VALUES (1, '甲', '乙')");
        }
        boolean teamMemberTableExists = tableExists("team_member");
        if (!teamMemberTableExists) {
            jdbc.execute("CREATE TABLE team_member ("
                    + "id INTEGER PRIMARY KEY, "
                    + "name TEXT NOT NULL UNIQUE)");
        }
        boolean templateTableExists = tableExists("scoreboard_template_config");
        if (!templateTableExists) {
            jdbc.execute("CREATE TABLE scoreboard_template_config ("
                    + "id INTEGER PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "opacity INTEGER NOT NULL DEFAULT 80, "
                    + "bg_color TEXT NOT NULL DEFAULT '#000000', "
                    + "text_color TEXT NOT NULL DEFAULT '#ffffff')");
        }
        boolean clipProjectTableExists = tableExists("clip_project");
        if (!clipProjectTableExists) {
            jdbc.execute("CREATE TABLE clip_project ("
                    + "id INTEGER PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "video_asset_id INTEGER NOT NULL REFERENCES video_asset(id), "
                    + "large_step INTEGER NOT NULL DEFAULT 10 CHECK (large_step > 0), "
                    + "small_step INTEGER NOT NULL DEFAULT 5 CHECK (small_step > 0), "
                    + "source_file_size INTEGER NOT NULL, "
                    + "source_modified_at INTEGER NOT NULL, "
                    + "total_frames INTEGER NULL, "
                    + "frame_index_status TEXT NOT NULL DEFAULT 'PENDING' "
                    + "CHECK (frame_index_status IN ('PENDING','BUILDING','READY','FAILED')), "
                    + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        }
        boolean clipSegmentTableExists = tableExists("clip_segment");
        if (!clipSegmentTableExists) {
            jdbc.execute("CREATE TABLE clip_segment ("
                    + "id INTEGER PRIMARY KEY, "
                    + "project_id INTEGER NOT NULL REFERENCES clip_project(id) ON DELETE CASCADE, "
                    + "segment_type TEXT NOT NULL CHECK (segment_type IN ('KEEP','DROP')), "
                    + "start_frame INTEGER NOT NULL CHECK (start_frame >= 0), "
                    + "end_frame INTEGER NOT NULL CHECK (end_frame > start_frame), "
                    + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_clip_segment_project "
                    + "ON clip_segment(project_id, start_frame)");
        }
        boolean clipRenderJobTableExists = tableExists("clip_render_job");
        if (!clipRenderJobTableExists) {
            jdbc.execute("CREATE TABLE clip_render_job ("
                    + "id INTEGER PRIMARY KEY, "
                    + "project_id INTEGER NOT NULL REFERENCES clip_project(id), "
                    + "status TEXT NOT NULL, "
                    + "progress_percent INTEGER NOT NULL DEFAULT 0, "
                    + "output_path TEXT NULL, "
                    + "output_asset_id INTEGER NULL, "
                    + "failure_message TEXT NULL, "
                    + "created_at TEXT NOT NULL, started_at TEXT NULL, finished_at TEXT NULL)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_clip_render_job_status ON clip_render_job(status)");
        }
    }

    private boolean tableExists(String tableName) {
        try (ResultSet rs = dataSource.getConnection().getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }
}
