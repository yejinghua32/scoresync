package com.scoresync.repository;

/**
 * 渲染任务仓库，负责管理视频渲染任务的创建和状态跟踪。
 * 同一时刻仅允许一个活跃任务（QUEUED 或 RUNNING 状态）。
 */

import com.scoresync.domain.RenderJob;
import com.scoresync.domain.RenderJobStatus;
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
import java.util.Optional;

@Repository
public class RenderJobRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<RenderJob> ROW_MAPPER = (rs, rowNum) ->
            new RenderJob(rs.getLong("id"), rs.getLong("match_id"),
                    ScoreboardTemplate.valueOf(rs.getString("template_code")),
                    RenderJobStatus.valueOf(rs.getString("status")),
                    rs.getInt("progress_percent"),
                    rs.getString("output_path"),
                    rs.getString("failure_message"),
                    parse(rs.getString("created_at")),
                    parse(rs.getString("started_at")),
                    parse(rs.getString("finished_at")));

    public RenderJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("render_job").usingGeneratedKeyColumns("id");
    }

    private static Instant parse(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String format(Instant value) {
        return value == null ? null : value.toString();
    }

    public long insert(long matchId, ScoreboardTemplate template) {
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("match_id", matchId)
                .addValue("template_code", template.name())
                .addValue("status", RenderJobStatus.QUEUED.name())
                .addValue("progress_percent", 0)
                .addValue("output_path", null)
                .addValue("failure_message", null)
                .addValue("created_at", now.toString())
                .addValue("started_at", null)
                .addValue("finished_at", null);
        return insert.executeAndReturnKey(params).longValue();
    }

    public RenderJob findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM render_job WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw new DomainNotFoundException("渲染任务不存在");
        }
    }

    /**
     * 查询当前活跃的渲染任务（QUEUED 或 RUNNING）
     *
     * @return 活跃任务（如果存在）
     */
    public Optional<RenderJob> findActive() {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM render_job WHERE status IN ('QUEUED','RUNNING') ORDER BY id DESC LIMIT 1",
                    ROW_MAPPER));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<RenderJob> findByStatus(RenderJobStatus status) {
        return jdbc.query("SELECT * FROM render_job WHERE status = ?", ROW_MAPPER, status.name());
    }

    public Optional<RenderJob> findActiveByMatchId(long matchId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM render_job WHERE match_id = ? AND status IN ('QUEUED','RUNNING') ORDER BY id DESC LIMIT 1",
                    ROW_MAPPER, matchId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int deleteByMatchId(long matchId) {
        return jdbc.update("DELETE FROM render_job WHERE match_id = ?", matchId);
    }

    public int update(RenderJob job) {
        return jdbc.update("""
                        UPDATE render_job SET status = ?, progress_percent = ?, output_path = ?, failure_message = ?,
                        started_at = ?, finished_at = ? WHERE id = ?""",
                job.status().name(), job.progressPercent(), job.outputPath(), job.failureMessage(),
                format(job.startedAt()), format(job.finishedAt()), job.id());
    }
}
