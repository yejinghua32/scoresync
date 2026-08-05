package com.scoresync.repository;

/**
 * 视频资源仓库，负责管理单个视频文件的元数据。
 * 记录文件的绝对路径、显示名称、大小、修改时间等信息。
 */

import com.scoresync.domain.VideoAsset;
import com.scoresync.domain.VideoOriginType;
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
public class VideoAssetRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<VideoAsset> ROW_MAPPER = (rs, rowNum) ->
            new VideoAsset(rs.getLong("id"), rs.getObject("source_id") != null ? rs.getLong("source_id") : null,
                    rs.getString("absolute_path"), rs.getString("display_name"),
                    rs.getLong("file_size"), rs.getLong("modified_at"),
                    VideoOriginType.valueOf(rs.getString("origin_type")),
                    Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));

    public VideoAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("video_asset").usingGeneratedKeyColumns("id");
    }

    /**
     * 新增视频资源记录
     *
     * @param sourceId     关联的视频源ID（可为null）
     * @param absolutePath 文件绝对路径
     * @param displayName  显示名称
     * @param fileSize     文件大小
     * @param modifiedAt   文件修改时间戳
     * @param originType   视频来源类型
     * @return 新建记录的主键ID
     */
    public long insert(Long sourceId, String absolutePath, String displayName,
                       long fileSize, long modifiedAt, VideoOriginType originType) {
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("source_id", sourceId)
                .addValue("absolute_path", absolutePath)
                .addValue("display_name", displayName)
                .addValue("file_size", fileSize)
                .addValue("modified_at", modifiedAt)
                .addValue("origin_type", originType.name())
                .addValue("created_at", now.toString())
                .addValue("updated_at", now.toString());
        return insert.executeAndReturnKey(params).longValue();
    }

    public VideoAsset findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM video_asset WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw new DomainNotFoundException("视频资源不存在");
        }
    }

    public List<VideoAsset> findAll() {
        return jdbc.query("SELECT * FROM video_asset", ROW_MAPPER);
    }

    public int update(VideoAsset asset) {
        return jdbc.update("""
                        UPDATE video_asset SET source_id = ?, absolute_path = ?, display_name = ?,
                        file_size = ?, modified_at = ?, origin_type = ?, updated_at = ? WHERE id = ?""",
                asset.sourceId(), asset.absolutePath(), asset.displayName(),
                asset.fileSize(), asset.modifiedAt(), asset.originType().name(),
                Instant.now().toString(), asset.id());
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM video_asset WHERE id = ?", id);
    }
}
