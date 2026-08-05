package com.scoresync.domain;

/**
 * 比分牌提示点
 * 在特定时间点需要渲染的比分牌状态，用于生成渲染指令序列
 *
 * @param startTimeMs    开始时间（毫秒）
 * @param sequenceNo     对应的事件序号
 * @param setNumber      当前局号
 * @param scoreA         A 方得分
 * @param scoreB         B 方得分
 * @param setWinsA       A 方已胜局数
 * @param setWinsB       B 方已胜局数
 * @param matchCompleted 比赛是否已完成
 */
public record ScoreboardCue(long startTimeMs, long sequenceNo, int setNumber,
                            int scoreA, int scoreB, int setWinsA, int setWinsB,
                            boolean matchCompleted) {
}
