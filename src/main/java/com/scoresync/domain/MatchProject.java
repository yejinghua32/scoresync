package com.scoresync.domain;

import java.time.Instant;

/**
 * 比赛项目
 * 表示一场完整的乒乓球比赛，包含视频、选手信息及比赛规则
 *
 * @param id                 唯一标识符
 * @param name               项目名称
 * @param videoAssetId       关联的视频资产 ID
 * @param durationMs         视频时长（毫秒）
 * @param playerA            A 方选手名称
 * @param playerB            B 方选手名称
 * @param targetWins         获胜所需局数
 * @param scoreboardTemplate 使用的比分牌模板
 * @param firstServer        首局发球方
 * @param createdAt          创建时间
 * @param updatedAt          最后更新时间
 */
public record MatchProject(long id, String name, long videoAssetId, long durationMs,
                           String playerA, String playerB, int targetWins,
                           ScoreboardTemplate scoreboardTemplate, PlayerSide firstServer,
                           Instant createdAt, Instant updatedAt) {
}
