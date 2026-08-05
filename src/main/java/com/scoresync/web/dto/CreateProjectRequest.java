package com.scoresync.web.dto;

/**
 * 创建比赛项目的请求体
 */

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record CreateProjectRequest(
        @NotBlank String name,
        @Positive long videoId,
        @Positive long durationMs,
        @NotBlank String playerA,
        @NotBlank String playerB,
        @Positive int targetWins) {
}
