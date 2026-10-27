package com.scoresync.repository;

/**
 * 剪辑导出任务仓库
 * 管理剪辑导出任务的创建和状态跟踪
 */

import com.scoresync.domain.ClipRenderJob;
import com.scoresync.domain.ClipRenderJobStatus;
import com.scoresync.support.DomainNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ClipRenderJobRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<ClipRenderJob> ROW_MAPPER = (rs, rowNum) ->
            new ClipRenderJob(rs.getLong("id"), rs.getLong("project_id"),
                    ClipRenderJobStatus.valueOf(rs.getString("status")),
                    rs.getInt("progress_percent"),
                    rs.getString("output_path"),
                    rs.getObject("output_asset_id") != null ? rs.getLong("output_asset_id") : null,
                    rs.getString("failure_message"),
                    parse(rs.getString("created_at")),
                    parse(rs.getString("started_at")),
                    parse(rs.getString("finished_at")));

    public ClipRenderJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("clip_render_job").usingGeneratedKeyColumns("id");
    }

    private static Instant parse(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String format(Instant value) {
        return value == null ? null : value.toString();
    }

    public long insert(long projectId) {
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("project_id", projectId)
                .addValue("status", ClipRenderJobStatus.QUEUED.name())
                .addValue("progress_percent", 0)
                .addValue("output_path", null)
                .addValue("output_asset_id", null)
                .addValue("failure_message", null)
                .addValue("created_at", now.toString())
                .addValue("started_at", null)
                .addValue("finished_at", null);
        return insert.executeAndReturnKey(params).longValue();
    }

    public ClipRenderJob findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM clip_render_job WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw new DomainNotFoundException("剪辑导出任务不存在");
        }
    }

    /**
     * 查询当前活跃的剪辑导出任务（QUEUED 或 RUNNING）
     */
    public Optional<ClipRenderJob> findActive() {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM clip_render_job WHERE status IN ('QUEUED','RUNNING') ORDER BY id DESC LIMIT 1",
                    ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ClipRenderJob> findByStatus(ClipRenderJobStatus status) {
        return jdbc.query("SELECT * FROM clip_render_job WHERE status = ?", ROW_MAPPER, status.name());
    }

    public List<ClipRenderJob> findByProjectId(long projectId) {
        return jdbc.query("SELECT * FROM clip_render_job WHERE project_id = ? ORDER BY id DESC", ROW_MAPPER, projectId);
    }

    public int update(ClipRenderJob job) {
        return jdbc.update("""
                        UPDATE clip_render_job SET status = ?, progress_percent = ?, output_path = ?,
                        output_asset_id = ?, failure_message = ?, started_at = ?, finished_at = ? WHERE id = ?""",
                job.status().name(), job.progressPercent(), job.outputPath(), job.outputAssetId(),
                job.failureMessage(), format(job.startedAt()), format(job.finishedAt()), job.id());
    }
}
