package com.scoresync.web.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 分页响应体
 */
public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {

    /**
     * 逐项转换，页码与总数保持不变
     */
    public <R> PageResponse<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = new ArrayList<>(items.size());
        for (T item : items) {
            mapped.add(mapper.apply(item));
        }
        return new PageResponse<>(mapped, page, size, total, totalPages);
    }
}
