package com.scoresync.web.dto;

/**
 * 分页响应体
 */

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {
}
