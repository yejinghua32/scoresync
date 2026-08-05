package com.scoresync.web.dto;

/**
 * 项目概要信息（用于列表展示）
 */

import java.time.Instant;

public record ProjectSummary(long id, String name, String playerA, String playerB,
                             int targetWins, long videoAssetId, String videoName,
                             Instant createdAt, Instant updatedAt) {
}
