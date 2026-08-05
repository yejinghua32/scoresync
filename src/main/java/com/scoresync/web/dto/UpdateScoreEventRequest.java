package com.scoresync.web.dto;

/**
 * 更新得分事件的请求体
 * 用于修改已有得分事件的时间戳和得分方
 */

import com.scoresync.domain.PlayerSide;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateScoreEventRequest(@Min(0) long videoTimeMs, @NotNull PlayerSide playerSide) {
}
