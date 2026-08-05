package com.scoresync.domain;

import java.time.Instant;

/**
 * 比分事件
 * 存储在数据库中的原始得分记录，用于后续推导比分
 * 比分从不直接存储，而是通过 replay 全部事件计算得出
 *
 * @param id          唯一标识符
 * @param matchId     所属比赛项目 ID
 * @param videoTimeMs 视频时间戳（毫秒）
 * @param playerSide  得分方（A 或 B）
 * @param sequenceNo  事件序号（递增）
 * @param createdAt   创建时间
 * @param updatedAt   最后更新时间
 */
public record ScoreEvent(long id, long matchId, long videoTimeMs,
                         PlayerSide playerSide, long sequenceNo,
                         Instant createdAt, Instant updatedAt) {
}
