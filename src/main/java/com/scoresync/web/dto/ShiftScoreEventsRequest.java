package com.scoresync.web.dto;

/**
 * 批量平移得分事件时间的请求体
 * 只允许 +100ms 与 -100ms 两种步长，其余值按业务校验错误处理
 */

public record ShiftScoreEventsRequest(long deltaMs) {
}