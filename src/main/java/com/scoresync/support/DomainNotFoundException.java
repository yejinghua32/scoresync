package com.scoresync.support;

/**
 * 资源不存在异常
 * 当请求的资源（项目、视频等）找不到时抛出（404 Not Found）
 */
public class DomainNotFoundException extends RuntimeException {
    public DomainNotFoundException(String message) {
        super(message);
    }
}
