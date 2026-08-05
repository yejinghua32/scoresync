package com.scoresync.web.dto;

/**
 * 创建得分事件的请求体
 */

import com.scoresync.domain.PlayerSide;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateScoreEventRequest(@Min(0) long videoTimeMs, @NotNull PlayerSide playerSide) {
}
