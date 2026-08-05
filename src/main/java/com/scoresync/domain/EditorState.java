package com.scoresync.domain;

/**
 * 编辑器状态
 * 记分编辑器界面的完整状态，包含项目、视频、回放结果及相关数据
 *
 * @param project        比赛项目
 * @param video          视频资产
 * @param match          回放结果（包含推导比分）
 * @param events         得分事件列表
 * @param scoreboardCues 比分牌提示点列表
 */
public record EditorState(MatchProject project, VideoAsset video,
                          ReplayResult match, java.util.List<DerivedScoreEvent> events,
                          java.util.List<ScoreboardCue> scoreboardCues) {
}
