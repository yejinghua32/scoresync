package com.scoresync.domain;

import java.time.Instant;

/**
 * 渲染任务
 * 存储渲染任务的完整信息及执行状态
 *
 * @param id              唯一标识符
 * @param matchId         关联的比赛项目 ID
 * @param templateCode    使用的比分牌模板
 * @param status          当前状态
 * @param progressPercent 进度百分比（0-100）
 * @param outputPath      输出文件路径
 * @param failureMessage  失败原因（可为空）
 * @param createdAt       创建时间
 * @param startedAt       开始执行时间（可为 null）
 * @param finishedAt      完成时间（可为 null）
 */
public record RenderJob(long id, long matchId, ScoreboardTemplate templateCode, RenderJobStatus status,
                        int progressPercent, String outputPath, String failureMessage,
                        Instant createdAt, Instant startedAt, Instant finishedAt) {
}
