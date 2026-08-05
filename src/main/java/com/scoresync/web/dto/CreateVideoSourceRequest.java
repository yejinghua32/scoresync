package com.scoresync.web.dto;

/**
 * 创建视频源的请求体
 */

import jakarta.validation.constraints.NotBlank;

public record CreateVideoSourceRequest(@NotBlank String rootPath) {
}
