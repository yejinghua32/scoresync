package com.scoresync.domain;

/**
 * 帧索引状态
 * PENDING  - 尚未建立索引
 * BUILDING - 正在建立索引（后台线程执行）
 * READY    - 索引可用，帧总数已知
 * FAILED   - 索引建立失败，可重试
 */
public enum ClipFrameIndexStatus {
    PENDING, BUILDING, READY, FAILED
}
