package com.scoresync.support;

/**
 * 视频仍在使用中的异常
 * 当尝试移除仍被比赛项目引用的视频资产时抛出（409 Conflict）
 */
public class VideoInUseException extends RuntimeException {
    private final String code;

    public VideoInUseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}