package com.scoresync.support;

/**
 * 媒体文件不可用异常
 * 当请求的视频文件不存在或无法访问时抛出（416 Range Not Satisfiable）
 */
public class MediaUnavailableException extends RuntimeException {
    public MediaUnavailableException(String message) {
        super(message);
    }
}
