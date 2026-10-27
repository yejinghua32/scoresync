package com.scoresync.domain;

import java.time.Instant;

/**
 * 剪辑导出任务
 * 记录剪辑项目的导出过程及结果
 *
 * @param id              唯一标识符
 * @param projectId       所属剪辑项目 ID
 * @param status          当前状态
 * @param progressPercent 进度百分比（0-100）
 * @param outputPath      输出文件路径
 * @param outputAssetId   成功后登记到视频库的新资产 ID（可为空）
 * @param failureMessage  失败原因（可为空）
 * @param createdAt       创建时间
 * @param startedAt       开始执行时间（可为空）
 * @param finishedAt      完成时间（可为空）
 */
public record ClipRenderJob(long id, long projectId, ClipRenderJobStatus status,
                            int progressPercent, String outputPath, Long outputAssetId,
                            String failureMessage, Instant createdAt, Instant startedAt,
                            Instant finishedAt) {
}
