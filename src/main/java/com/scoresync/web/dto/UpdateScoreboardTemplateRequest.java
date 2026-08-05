package com.scoresync.web.dto;

/**
 * 更新项目记分板模板的请求体
 */

import com.scoresync.domain.ScoreboardTemplate;
import jakarta.validation.constraints.NotNull;

public record UpdateScoreboardTemplateRequest(@NotNull ScoreboardTemplate templateCode) {
}
