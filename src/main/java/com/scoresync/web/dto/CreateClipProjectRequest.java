package com.scoresync.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 创建剪辑项目的请求体
 */
public record CreateClipProjectRequest(
        @NotBlank String name,
        @Positive long videoId) {
}
