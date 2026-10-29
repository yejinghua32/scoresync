package com.scoresync.domain;

import java.time.Instant;

/**
 * 剪辑片段
 * 表示源视频中一段连续的帧区间，采用左闭右开区间 [startFrame, endFrame)
 *
 * @param id         唯一标识符
 * @param projectId  所属剪辑项目 ID
 * @param type       片段类型（保留或舍弃）
 * @param startFrame 起始帧序号（含）
 * @param endFrame   结束帧序号（不含）
 * @param featured   是否精选，只有保留片段可以是精选
 * @param createdAt  创建时间
 * @param updatedAt  最后更新时间
 */
public record ClipSegment(long id, long projectId, ClipSegmentType type, int startFrame, int endFrame,
                           boolean featured, Instant createdAt, Instant updatedAt) {
}
