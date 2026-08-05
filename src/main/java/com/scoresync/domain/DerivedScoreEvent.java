package com.scoresync.domain;

/**
 * 推导后的比分事件
 * 基于原始 ScoreEvent 推导出的完整比分状态，包含事件发生时的一切比分信息
 *
 * @param eventId     原始事件 ID
 * @param videoTimeMs 视频时间戳（毫秒）
 * @param playerSide  得分方
 * @param sequenceNo  事件序号
 * @param setNo       发生时的局号
 * @param scoreA      发生后的 A 方得分
 * @param scoreB      发生后的 B 方得分
 * @param setWinsA    A 方已胜局数
 * @param setWinsB    B 方已胜局数
 */
public record DerivedScoreEvent(long eventId, long videoTimeMs, PlayerSide playerSide,
                                long sequenceNo, int setNo, int scoreA, int scoreB,
                                int setWinsA, int setWinsB) {
}
