package com.scoresync.domain;

/**
 * 单局比分
 * 表示某一时刻某一局的比分，用于比分牌按局展示
 *
 * @param setNumber 局号（1-based）
 * @param scoreA    A 方该局得分
 * @param scoreB    B 方该局得分
 */
public record SetScore(int setNumber, int scoreA, int scoreB) {
}
