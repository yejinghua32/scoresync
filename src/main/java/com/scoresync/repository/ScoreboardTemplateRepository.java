package com.scoresync.repository;

/**
 * 比分牌模板配置仓库，负责管理比分牌的样式配置。
 * 包括模板名称、透明度、背景色、文字颜色等。
 */

import com.scoresync.domain.ScoreboardTemplateConfig;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class ScoreboardTemplateRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<ScoreboardTemplateConfig> ROW_MAPPER = (rs, rowNum) ->
            new ScoreboardTemplateConfig(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getInt("opacity"),
                    rs.getString("bg_color"),
                    rs.getString("text_color"));

    public ScoreboardTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("scoreboard_template_config")
                .usingGeneratedKeyColumns("id");
    }

    public long insert(ScoreboardTemplateConfig config) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", config.name())
                .addValue("opacity", config.opacity())
                .addValue("bg_color", config.bgColor())
                .addValue("text_color", config.textColor());
        return insert.executeAndReturnKey(params).longValue();
    }

    public int update(ScoreboardTemplateConfig config) {
        return jdbc.update(
                "UPDATE scoreboard_template_config SET name = ?, opacity = ?, bg_color = ?, text_color = ? WHERE id = ?",
                config.name(), config.opacity(), config.bgColor(), config.textColor(), config.id());
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM scoreboard_template_config WHERE id = ?", id);
    }

    public ScoreboardTemplateConfig findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM scoreboard_template_config WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public java.util.List<ScoreboardTemplateConfig> findAll() {
        return jdbc.query("SELECT * FROM scoreboard_template_config", ROW_MAPPER);
    }
}
