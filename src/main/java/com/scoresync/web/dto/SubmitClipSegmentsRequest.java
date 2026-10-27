package com.scoresync.web.dto;

import jakarta.validation.Valid;

import java.util.List;

/**
 * 提交片段的请求体
 * 按提交顺序依次覆盖已有区间
 */
public record SubmitClipSegmentsRequest(
        @Valid List<ClipSegmentRequest> segments) {
}
