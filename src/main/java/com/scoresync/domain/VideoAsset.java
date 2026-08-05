package com.scoresync.domain;

import java.time.Instant;

/**
 * 视频资产
 * 已登记的视频文件，包含文件路径、元信息及来源类型
 *
 * @param id           唯一标识符
 * @param sourceId     所属视频源的ID（可为 null）
 * @param absolutePath 视频文件的绝对路径
 * @param displayName  显示名称
 * @param fileSize     文件大小（字节）
 * @param modifiedAt   文件最后修改时间
 * @param originType   来源类型（扫描或手动选择）
 * @param createdAt    登记时间
 * @param updatedAt    最后更新时间
 */
public record VideoAsset(long id, Long sourceId, String absolutePath, String displayName,
                         long fileSize, long modifiedAt, VideoOriginType originType,
                         Instant createdAt, Instant updatedAt) {
}
