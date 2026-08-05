package com.scoresync.web.dto;

/**
 * API 错误响应体
 * 包含错误码、消息和字段级错误信息
 */

import java.util.Map;

public record ApiError(String code, String message, Map<String, String> fields) {
}
