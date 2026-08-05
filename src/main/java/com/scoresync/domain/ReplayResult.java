package com.scoresync.domain;

/**
 * 回放结果
 * 通过 replay 所有 ScoreEvent 推导出的完整比赛状态
 * 包含已完成的所有局、当前比分、所有得分事件及渲染提示点
 *
 * @param completedSets  已完成的局列表
 * @param events         推导后的得分事件列表
 * @param currentSetNo   当前局号
 * @param currentScoreA  A 方当前得分
 * @param currentScoreB  B 方当前得分
 * @param setWinsA       A 方已胜局数
 * @param setWinsB       B 方已胜局数
 * @param matchCompleted 比赛是否已完成
 * @param scoreboardCues 比分牌渲染提示点列表
 */
public record ReplayResult(java.util.List<SetSummary> completedSets, java.util.List<DerivedScoreEvent> events,
                           int currentSetNo, int currentScoreA, int currentScoreB,
                           int setWinsA, int setWinsB, boolean matchCompleted,
                           java.util.List<ScoreboardCue> scoreboardCues) {
}
