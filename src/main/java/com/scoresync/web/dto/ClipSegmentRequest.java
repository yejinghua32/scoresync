package com.scoresync.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 剪辑片段请求体
 * 区间为左闭右开 [startFrame, endFrame)
 */
public record ClipSegmentRequest(
        @NotBlank String type,
        int startFrame,
        int endFrame) {
}
