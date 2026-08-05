package com.scoresync.repository;

/**
 * 比赛项目仓库，负责管理比赛项目的主数据。
 * 包含项目名称、关联视频、选手信息、赛制（目标胜局数）和初始发球方等。
 */

import com.scoresync.domain.MatchProject;
import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreboardTemplate;
import com.scoresync.support.DomainNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class MatchProjectRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<MatchProject> ROW_MAPPER = (rs, rowNum) ->
            new MatchProject(rs.getLong("id"), rs.getString("name"), rs.getLong("video_asset_id"),
                    rs.getLong("duration_ms"), rs.getString("player_a"), rs.getString("player_b"),
                    rs.getInt("target_wins"), ScoreboardTemplate.valueOf(rs.getString("scoreboard_template")),
                    PlayerSide.valueOf(rs.getString("first_server")),
                    Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));

    public MatchProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("match_project").usingGeneratedKeyColumns("id");
    }

    /**
     * 新建比赛项目
     *
     * @return 新建记录的主键ID
     */
    public long insert(String name, long videoAssetId, long durationMs,
                       String playerA, String playerB, int targetWins,
                       ScoreboardTemplate scoreboardTemplate, PlayerSide firstServer) {
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("video_asset_id", videoAssetId)
                .addValue("duration_ms", durationMs)
                .addValue("player_a", playerA)
                .addValue("player_b", playerB)
                .addValue("target_wins", targetWins)
                .addValue("scoreboard_template", scoreboardTemplate.name())
                .addValue("first_server", firstServer.name())
                .addValue("created_at", now.toString())
                .addValue("updated_at", now.toString());
        return insert.executeAndReturnKey(params).longValue();
    }

    public MatchProject findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM match_project WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw new DomainNotFoundException("比赛项目不存在");
        }
    }

    public List<MatchProject> findAll() {
        return jdbc.query("SELECT * FROM match_project", ROW_MAPPER);
    }

    /**
     * 分页查询比赛项目
     *
     * @param offset 起始偏移
     * @param limit  返回数量上限
     * @return 比赛项目列表
     */
    public List<MatchProject> findAll(int offset, int limit) {
        return jdbc.query("SELECT * FROM match_project ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, limit, offset);
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM match_project", Integer.class);
        return count == null ? 0 : count;
    }

    public int update(MatchProject project) {
        return jdbc.update("""
                        UPDATE match_project SET name = ?, video_asset_id = ?, duration_ms = ?,
                        player_a = ?, player_b = ?, target_wins = ?, scoreboard_template = ?,
                        first_server = ?, updated_at = ? WHERE id = ?""",
                project.name(), project.videoAssetId(), project.durationMs(),
                project.playerA(), project.playerB(), project.targetWins(),
                project.scoreboardTemplate().name(),
                project.firstServer().name(),
                Instant.now().toString(), project.id());
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM match_project WHERE id = ?", id);
    }
}
