package com.scoresync.repository;

/**
 * 队员仓库，负责管理参赛队员信息。
 * 队员数据用于在记分系统中标识两侧选手。
 */

import com.scoresync.domain.TeamMember;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TeamMemberRepository {
    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    private static final RowMapper<TeamMember> ROW_MAPPER = (rs, rowNum) ->
            new TeamMember(rs.getLong("id"), rs.getString("name"));

    public TeamMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("team_member").usingGeneratedKeyColumns("id");
    }

    public List<TeamMember> findAll() {
        return jdbc.query("SELECT id, name FROM team_member ORDER BY id", ROW_MAPPER);
    }

    public TeamMember findById(long id) {
        return jdbc.queryForObject("SELECT id, name FROM team_member WHERE id = ?", ROW_MAPPER, id);
    }

    /**
     * 按名称查找队员
     *
     * @param name 队员名称
     * @return 已登记的队员，不存在时为空
     */
    public Optional<TeamMember> findByName(String name) {
        return jdbc.query("SELECT id, name FROM team_member WHERE name = ?", ROW_MAPPER, name)
                .stream().findFirst();
    }

    /**
     * 新增队员
     * 使用生成键而非 last_insert_rowid()，避免 INSERT 与取号落在不同连接上时取到错误ID
     *
     * @param name 队员名称
     * @return 新建记录的主键ID
     */
    public long insert(String name) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("name", name);
        return insert.executeAndReturnKey(params).longValue();
    }

    public void update(long id, String name) {
        jdbc.update("UPDATE team_member SET name = ? WHERE id = ?", name, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM team_member WHERE id = ?", id);
    }
}
