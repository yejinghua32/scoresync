package com.scoresync.web.dto;

import com.scoresync.domain.ClipProject;
import com.scoresync.domain.ClipProjectDetail;

import java.time.Instant;
import java.util.List;

/**
 * 剪辑项目响应体
 * 包含项目配置、帧索引状态与已存档片段
 */
public record ClipProjectResponse(long id, String name, long videoAssetId, String videoName,
                                  int largeStep, int smallStep, long sourceFileSize, long sourceModifiedAt,
                                  Integer totalFrames, String frameIndexStatus,
                                  List<ClipSegmentResponse> segments,
                                  Instant createdAt, Instant updatedAt) {

    public static ClipProjectResponse from(ClipProjectDetail detail) {
        ClipProject project = detail.project();
        return new ClipProjectResponse(project.id(), project.name(), project.videoAssetId(),
                detail.video().displayName(), project.largeStep(), project.smallStep(),
                project.sourceFileSize(), project.sourceModifiedAt(), project.totalFrames(),
                project.frameIndexStatus().name(),
                detail.segments().stream().map(ClipSegmentResponse::from).toList(),
                project.createdAt(), project.updatedAt());
    }
}
