package com.scoresync.web.dto;

import java.time.Instant;

/**
 * 帧索引状态响应体
 * 前端据此显示加载中、失败重试或可用状态
 *
 * @param status      状态（PENDING / BUILDING / READY / FAILED）
 * @param totalFrames 总帧数（索引就绪后才有值）
 * @param updatedAt   最后更新时间
 */
public record ClipFrameIndexStatusResponse(String status, Integer totalFrames, Instant updatedAt) {
}
