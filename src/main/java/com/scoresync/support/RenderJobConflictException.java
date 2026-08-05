package com.scoresync.support;

/**
 * 渲染任务冲突异常
 * 当尝试创建新任务但已存在活跃任务时抛出（409 Conflict）
 */
public class RenderJobConflictException extends RuntimeException {
    private final String code;

    public RenderJobConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
