package com.scoresync.repository;

/**
 * 得分事件仓库，负责管理比赛中每次得分的记录。
 * 比分从得分事件派生，不直接存储局比分。
 * 通过 video_time_ms 和 sequence_no 确定得分时刻。
 */

import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreEvent;
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
public class ScoreEventRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<ScoreEvent> ROW_MAPPER = (rs, rowNum) ->
            new ScoreEvent(rs.getLong("id"), rs.getLong("match_id"), rs.getLong("video_time_ms"),
                    PlayerSide.valueOf(rs.getString("player_side")), rs.getLong("sequence_no"),
                    Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));

    public ScoreEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("score_event").usingGeneratedKeyColumns("id");
    }

    /**
     * 插入得分事件，自动分配 sequence_no
     *
     * @param matchId     比赛ID
     * @param videoTimeMs 视频时间戳（毫秒）
     * @param playerSide  得分方
     * @return 新建记录的主键ID
     */
    public long insert(long matchId, long videoTimeMs, PlayerSide playerSide) {
        long sequenceNo = allocateSequenceNo(matchId);
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("match_id", matchId)
                .addValue("video_time_ms", videoTimeMs)
                .addValue("player_side", playerSide.name())
                .addValue("sequence_no", sequenceNo)
                .addValue("created_at", now.toString())
                .addValue("updated_at", now.toString());
        return insert.executeAndReturnKey(params).longValue();
    }

    private long allocateSequenceNo(long matchId) {
        Long maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM score_event WHERE match_id = ?",
                Long.class, matchId);
        return maxSeq != null ? maxSeq : 1;
    }

    public List<ScoreEvent> findByMatchId(long matchId) {
        return jdbc.query("""
                        SELECT id, match_id, video_time_ms, player_side, sequence_no, created_at, updated_at
                        FROM score_event WHERE match_id = ?
                        ORDER BY video_time_ms ASC, sequence_no ASC""",
                ROW_MAPPER, matchId);
    }

    /**
     * 查询指定比赛的最新得分事件
     *
     * @param matchId 比赛ID
     * @return 最新的得分事件（如果存在）
     */
    public Optional<ScoreEvent> findMostRecentByMatchId(long matchId) {
        return jdbc.query("""
                        SELECT id, match_id, video_time_ms, player_side, sequence_no, created_at, updated_at
                        FROM score_event WHERE match_id = ?
                        ORDER BY sequence_no DESC LIMIT 1""",
                ROW_MAPPER, matchId).stream().findFirst();
    }

    public ScoreEvent findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM score_event WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw new DomainNotFoundException("得分事件不存在");
        }
    }

    public ScoreEvent update(long id, long videoTimeMs, PlayerSide playerSide) {
        int rows = jdbc.update("""
                        UPDATE score_event SET video_time_ms = ?, player_side = ?, updated_at = ? WHERE id = ?""",
                videoTimeMs, playerSide.name(), Instant.now().toString(), id);
        if (rows == 0) {
            throw new DomainNotFoundException("得分事件不存在");
        }
        return findById(id);
    }

    public void delete(long id) {
        int rows = jdbc.update("DELETE FROM score_event WHERE id = ?", id);
        if (rows == 0) {
            throw new DomainNotFoundException("得分事件不存在");
        }
    }

    public int deleteByMatchId(long matchId) {
        return jdbc.update("DELETE FROM score_event WHERE match_id = ?", matchId);
    }
}
