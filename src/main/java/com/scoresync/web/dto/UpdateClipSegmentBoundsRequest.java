package com.scoresync.web.dto;

/**
 * 调整片段边界的请求体
 * 区间为左闭右开 [startFrame, endFrame)
 */
public record UpdateClipSegmentBoundsRequest(int startFrame, int endFrame) {
}
