package com.scoresync.web.dto;

/**
 * 替换项目视频的请求体
 */

import jakarta.validation.constraints.Positive;

public record ReplaceVideoRequest(
        @Positive long videoAssetId) {
}
