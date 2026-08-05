package com.scoresync.web.dto;

/**
 * 更新首发发球方的请求体
 */

import com.scoresync.domain.PlayerSide;
import jakarta.validation.constraints.NotNull;

public record UpdateFirstServerRequest(@NotNull PlayerSide firstServer) {
}
