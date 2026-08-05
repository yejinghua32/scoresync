package com.scoresync.domain;

/**
 * 局总结
 * 已完成的一局比赛结果，包含局号、双方得分及胜者
 *
 * @param setNo  局号（1-based）
 * @param scoreA A 方得分
 * @param scoreB B 方得分
 * @param winner 胜者（A 或 B）
 */
public record SetSummary(int setNo, int scoreA, int scoreB, PlayerSide winner) {
}
