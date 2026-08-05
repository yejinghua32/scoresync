package com.scoresync.support;

/**
 * 业务校验异常
 * 当业务规则校验失败时抛出（如向已结束比赛添加比分）（400 Bad Request）
 */
public class DomainValidationException extends RuntimeException {
    public DomainValidationException(String message) {
        super(message);
    }
}
