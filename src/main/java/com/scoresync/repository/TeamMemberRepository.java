package com.scoresync.repository;

/**
 * 队员仓库，负责管理参赛队员信息。
 * 队员数据用于在记分系统中标识两侧选手。
 */

import com.scoresync.domain.TeamMember;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TeamMemberRepository {
    private final JdbcTemplate jdbc;

    private static final RowMapper<TeamMember> ROW_MAPPER = (rs, rowNum) ->
            new TeamMember(rs.getLong("id"), rs.getString("name"));

    public TeamMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TeamMember> findAll() {
        return jdbc.query("SELECT id, name FROM team_member ORDER BY id", ROW_MAPPER);
    }

    public TeamMember findById(long id) {
        return jdbc.queryForObject("SELECT id, name FROM team_member WHERE id = ?", ROW_MAPPER, id);
    }

    public long insert(String name) {
        jdbc.update("INSERT INTO team_member (name) VALUES (?)", name);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public void update(long id, String name) {
        jdbc.update("UPDATE team_member SET name = ? WHERE id = ?", name, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM team_member WHERE id = ?", id);
    }
}
