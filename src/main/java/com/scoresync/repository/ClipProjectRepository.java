package com.scoresync.repository;

/**
 * 剪辑项目仓库
 * 管理剪辑项目的主数据：名称、关联视频、两个步长以及建立索引时的源文件指纹
 */

import com.scoresync.domain.ClipFrameIndexStatus;
import com.scoresync.domain.ClipProject;
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
public class ClipProjectRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private static final RowMapper<ClipProject> ROW_MAPPER = (rs, rowNum) ->
            new ClipProject(rs.getLong("id"), rs.getString("name"), rs.getLong("video_asset_id"),
                    rs.getInt("large_step"), rs.getInt("small_step"),
                    rs.getLong("source_file_size"), rs.getLong("source_modified_at"),
                    rs.getObject("total_frames") != null ? rs.getInt("total_frames") : null,
                    ClipFrameIndexStatus.valueOf(rs.getString("frame_index_status")),
                    Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));

    public ClipProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc).withTableName("clip_project").usingGeneratedKeyColumns("id");
    }

    /**
     * 新建剪辑项目
     *
     * @return 新建记录的主键 ID
     */
    public long insert(String name, long videoAssetId, int largeStep, int smallStep,
                       long sourceFileSize, long sourceModifiedAt) {
        Instant now = Instant.now();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("video_asset_id", videoAssetId)
                .addValue("large_step", largeStep)
                .addValue("small_step", smallStep)
                .addValue("source_file_size", sourceFileSize)
                .addValue("source_modified_at", sourceModifiedAt)
                .addValue("total_frames", null)
                .addValue("frame_index_status", ClipFrameIndexStatus.PENDING.name())
                .addValue("created_at", now.toString())
                .addValue("updated_at", now.toString());
        return insert.executeAndReturnKey(params).longValue();
    }

    public ClipProject findById(long id) {
        try {
            return jdbc.queryForObject("SELECT * FROM clip_project WHERE id = ?", ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            throw new DomainNotFoundException("剪辑项目不存在");
        }
    }

    public List<ClipProject> findAll() {
        return jdbc.query("SELECT * FROM clip_project ORDER BY id DESC", ROW_MAPPER);
    }

    /**
     * 分页查询剪辑项目
     * 可按项目名称或所属视频名称过滤
     *
     * @param offset 起始偏移
     * @param limit  返回数量上限
     * @param search 关键字，为空时不过滤
     */
    public List<ClipProject> findPage(int offset, int limit, String search) {
        if (search == null || search.isBlank()) {
            return jdbc.query("SELECT * FROM clip_project ORDER BY id DESC LIMIT ? OFFSET ?",
                    ROW_MAPPER, limit, offset);
        }
        String like = "%" + search.trim() + "%";
        return jdbc.query("""
                SELECT c.* FROM clip_project c
                LEFT JOIN video_asset v ON v.id = c.video_asset_id
                WHERE c.name LIKE ? OR v.display_name LIKE ?
                ORDER BY c.id DESC LIMIT ? OFFSET ?""",
                ROW_MAPPER, like, like, limit, offset);
    }

    /**
     * 统计剪辑项目数量
     *
     * @param search 关键字，为空时统计全部
     */
    public int count(String search) {
        Integer count;
        if (search == null || search.isBlank()) {
            count = jdbc.queryForObject("SELECT COUNT(*) FROM clip_project", Integer.class);
        } else {
            String like = "%" + search.trim() + "%";
            count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM clip_project c
                    LEFT JOIN video_asset v ON v.id = c.video_asset_id
                    WHERE c.name LIKE ? OR v.display_name LIKE ?""", Integer.class, like, like);
        }
        return count == null ? 0 : count;
    }

    /**
     * 更新项目名称与两个步长
     */
    public int updateConfig(ClipProject project) {
        return jdbc.update("""
                        UPDATE clip_project SET name = ?, large_step = ?, small_step = ?, updated_at = ? WHERE id = ?""",
                project.name(), project.largeStep(), project.smallStep(),
                Instant.now().toString(), project.id());
    }

    /**
     * 更新帧索引状态与总帧数
     */
    public int updateFrameIndex(long id, ClipFrameIndexStatus status, Integer totalFrames) {
        return jdbc.update("""
                        UPDATE clip_project SET frame_index_status = ?, total_frames = ?, updated_at = ? WHERE id = ?""",
                status.name(), totalFrames, Instant.now().toString(), id);
    }

    /**
     * 统计引用指定视频资产的剪辑项目数量
     */
    public int countByVideoAssetId(long videoAssetId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM clip_project WHERE video_asset_id = ?", Integer.class, videoAssetId);
        return count == null ? 0 : count;
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM clip_project", Integer.class);
        return count == null ? 0 : count;
    }

    public int delete(long id) {
        return jdbc.update("DELETE FROM clip_project WHERE id = ?", id);
    }
}
