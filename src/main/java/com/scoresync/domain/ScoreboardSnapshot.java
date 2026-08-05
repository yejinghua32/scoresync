package com.scoresync.domain;

/**
 * 比分牌快照
 * 某一时刻比分牌的完整状态，用于渲染或预览
 *
 * @param template       使用的模板
 * @param playerA        A 方选手名称
 * @param playerB        B 方选手名称
 * @param scoreA         A 方当前得分
 * @param scoreB         B 方当前得分
 * @param setNumber      当前局号
 * @param setWinsA       A 方已胜局数
 * @param setWinsB       B 方已胜局数
 * @param matchCompleted 比赛是否已完成
 * @param firstServer    首局发球方
 * @param frameWidth     视频帧宽度
 * @param frameHeight    视频帧高度
 */
public record ScoreboardSnapshot(ScoreboardTemplate template, String playerA, String playerB,
                                 int scoreA, int scoreB, int setNumber,
                                 int setWinsA, int setWinsB, boolean matchCompleted,
                                 PlayerSide firstServer,
                                 int frameWidth, int frameHeight) {
}
