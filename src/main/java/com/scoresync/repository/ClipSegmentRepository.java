package com.scoresync.repository;

/**
 * 剪辑片段仓库
 * 保存互不重叠的 KEEP/DROP 半开帧区间；未标记帧由区间之间的空隙派生
 */

import com.scoresync.domain.ClipSegment;
import com.scoresync.domain.ClipSegmentType;
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
public class ClipSegmentRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<ClipSegment> ROW_MAPPER = (rs, rowNum) ->
            new ClipSegment(rs.getLong("id"), rs.getLong("project_id"),
                    ClipSegmentType.valueOf(rs.getString("segment_type")),
                    rs.getInt("start_frame"), rs.getInt("end_frame"),
                    Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));

    public ClipSegmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("clip_segment").usingGeneratedKeyColumns("id");
    }

    public long insert(long projectId, ClipSegmentType type, int startFrame, int endFrame) {
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("project_id", projectId)
                .addValue("segment_type", type.name())
                .addValue("start_frame", startFrame)
                .addValue("end_frame", endFrame)
                .addValue("created_at", now.toString())
                .addValue("updated_at", now.toString());
        return insert.executeAndReturnKey(params).longValue();
    }

    /**
     * 查询项目的全部片段，按起始帧升序
     */
    public List<ClipSegment> findByProjectId(long projectId) {
        return jdbc.query("SELECT * FROM clip_segment WHERE project_id = ? ORDER BY start_frame",
                ROW_MAPPER, projectId);
    }

    public ClipSegment findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM clip_segment WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw new DomainNotFoundException("剪辑片段不存在");
        }
    }

    public int deleteByProjectId(long projectId) {
        return jdbc.update("DELETE FROM clip_segment WHERE project_id = ?", projectId);
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM clip_segment WHERE id = ?", id);
    }
}
