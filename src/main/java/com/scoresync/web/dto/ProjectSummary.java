package com.scoresync.web.dto;

/**
 * 项目概要信息（用于列表展示）
 *
 * @param videoMissing 原视频不存在（例如导入备份时视频库中没有匹配的视频）
 */

import java.time.Instant;

public record ProjectSummary(long id, String name, String playerA, String playerB,
                             int targetWins, long videoAssetId, String videoName, boolean videoMissing,
                             boolean showProjectName,
                             Instant createdAt, Instant updatedAt) {
}
