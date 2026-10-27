package com.scoresync.support;

/**
 * 外部工具不可用异常
 * FFmpeg / FFprobe 未安装或路径配置错误时抛出（400 Bad Request）
 * 该错误是可恢复的：安装工具或修正配置后重试即可
 */
public class ToolUnavailableException extends RuntimeException {
    private final String code;

    public ToolUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
