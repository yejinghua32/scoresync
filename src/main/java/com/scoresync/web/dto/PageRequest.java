package com.scoresync.web.dto;

/**
 * 分页请求参数
 * 页码从 0 开始，每页数量上限 100
 *
 * @param page 页码
 * @param size 每页数量
 */
public record PageRequest(int page, int size) {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 100;

    public PageRequest {
        page = Math.max(0, page);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /**
     * 当前页的起始偏移
     */
    public int offset() {
        return page * size;
    }

    /**
     * 总页数
     *
     * @param total 记录总数
     */
    public int totalPages(long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.ceil((double) total / size);
    }
}
