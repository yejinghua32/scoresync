package com.scoresync.web.dto;

import com.scoresync.domain.RenderJob;
import com.scoresync.domain.RenderJobStatus;

import java.time.Instant;

/**
 * 渲染任务响应体
 * 包含任务状态、进度与输出文件信息
 */
public record RenderJobResponse(long id, long matchId, String templateCode, RenderJobStatus status,
                                int progressPercent, String outputFilename, String outputPath,
                                String failureMessage,
                                Instant createdAt, Instant startedAt, Instant finishedAt) {

    public static RenderJobResponse from(RenderJob job) {
        String outputFilename = null;
        if (job.outputPath() != null) {
            int slash = Math.max(job.outputPath().lastIndexOf('/'), job.outputPath().lastIndexOf('\\'));
            outputFilename = slash >= 0 ? job.outputPath().substring(slash + 1) : job.outputPath();
        }
        return new RenderJobResponse(job.id(), job.matchId(), job.templateCode().name(),
                job.status(), job.progressPercent(), outputFilename, job.outputPath(), job.failureMessage(),
                job.createdAt(), job.startedAt(), job.finishedAt());
    }
}
