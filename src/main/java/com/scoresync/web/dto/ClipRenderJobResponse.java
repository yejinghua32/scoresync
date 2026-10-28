package com.scoresync.web.dto;

import com.scoresync.domain.ClipRenderJob;

import java.time.Instant;

/**
 * 剪辑导出任务响应体
 */
public record ClipRenderJobResponse(long id, long projectId, String status, int progressPercent,
                                    String outputFilename, String outputPath, Long outputAssetId,
                                    String failureMessage,
                                    Instant createdAt, Instant startedAt, Instant finishedAt) {

    public static ClipRenderJobResponse from(ClipRenderJob job) {
        String outputFilename = null;
        if (job.outputPath() != null) {
            int slash = Math.max(job.outputPath().lastIndexOf('/'), job.outputPath().lastIndexOf('\\'));
            outputFilename = slash >= 0 ? job.outputPath().substring(slash + 1) : job.outputPath();
        }
        return new ClipRenderJobResponse(job.id(), job.projectId(), job.status().name(),
                job.progressPercent(), outputFilename, job.outputPath(), job.outputAssetId(),
                job.failureMessage(),
                job.createdAt(), job.startedAt(), job.finishedAt());
    }
}
