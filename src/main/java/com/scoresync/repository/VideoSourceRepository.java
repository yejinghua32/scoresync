package com.scoresync.repository;

/**
 * 视频源仓库，负责管理视频源目录的增删改查。
 * 视频源表示一个根目录路径，用于索引该目录下的视频文件。
 */

import com.scoresync.domain.VideoSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class VideoSourceRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<VideoSource> ROW_MAPPER = (rs, rowNum) ->
            new VideoSource(rs.getLong("id"), rs.getString("root_path"), rs.getInt("enabled") == 1,
                    Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));

    public VideoSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("video_source").usingGeneratedKeyColumns("id");
    }

    /**
     * 新增视频源目录
     *
     * @param rootPath 根目录路径
     * @return 新建的视频源对象
     */
    public VideoSource insert(String rootPath) {
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("root_path", rootPath)
                .addValue("enabled", 1)
                .addValue("created_at", now.toString())
                .addValue("updated_at", now.toString());
        long id = insert.executeAndReturnKey(params).longValue();
        return new VideoSource(id, rootPath, true, now, now);
    }

    public VideoSource findById(long id) {
        return jdbc.queryForObject("SELECT * FROM video_source WHERE id = ?", ROW_MAPPER, id);
    }

    public List<VideoSource> findAll() {
        return jdbc.query("SELECT * FROM video_source", ROW_MAPPER);
    }

    public int update(VideoSource source) {
        return jdbc.update("UPDATE video_source SET root_path = ?, enabled = ?, updated_at = ? WHERE id = ?",
                source.rootPath(), source.enabled() ? 1 : 0, Instant.now().toString(), source.id());
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM video_source WHERE id = ?", id);
    }
}
