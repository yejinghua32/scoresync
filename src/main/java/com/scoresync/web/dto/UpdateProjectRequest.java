package com.scoresync.web.dto;

/**
 * 更新项目基本信息的请求体
 */

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record UpdateProjectRequest(
        @NotBlank String name,
        @NotBlank String playerA,
        @NotBlank String playerB,
        @Positive int targetWins
) {
}
