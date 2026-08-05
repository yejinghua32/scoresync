package com.scoresync.repository;

/**
 * 应用设置仓库，负责存储和读取全局应用设置。
 * 当前仅包含默认选手名称（甲/乙）的配置。
 */

import com.scoresync.domain.Settings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {

    private final JdbcTemplate jdbc;
    private static final RowMapper<Settings> ROW_MAPPER = (rs, rowNum) ->
            new Settings(
                    rs.getString("player_a_name"),
                    rs.getString("player_b_name"));

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Settings find() {
        try {
            return jdbc.queryForObject("SELECT * FROM app_settings", ROW_MAPPER);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return new Settings("甲", "乙");
        }
    }

    public void save(Settings settings) {
        int count = jdbc.queryForObject("SELECT COUNT(*) FROM app_settings", Integer.class);
        if (count == 0) {
            jdbc.update("INSERT INTO app_settings (player_a_name, player_b_name) VALUES (?, ?)",
                    settings.playerAName(), settings.playerBName());
        } else {
            jdbc.update("UPDATE app_settings SET player_a_name = ?, player_b_name = ?",
                    settings.playerAName(), settings.playerBName());
        }
    }
}
