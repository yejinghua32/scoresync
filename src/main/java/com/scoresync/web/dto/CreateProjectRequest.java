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
        @Positive int targetWins,
        /** 比分牌队名列表头是否显示项目名称，默认不显示 */
        Boolean showProjectName) {

    public CreateProjectRequest(String name, long videoId, long durationMs,
                                String playerA, String playerB, int targetWins) {
        this(name, videoId, durationMs, playerA, playerB, targetWins, null);
    }

    /**
     * 比分牌队名列表头是否显示项目名称
     */
    public boolean showProjectNameOrDefault() {
        return showProjectName != null && showProjectName;
    }
}
