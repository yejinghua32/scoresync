package com.scoresync.domain;

/**
 * 渲染任务状态枚举
 * <p>
 * QUEUED   - 排队中，等待执行
 * RUNNING  - 执行中
 * SUCCEEDED - 已成功完成
 * FAILED   - 执行失败
 * CANCELLED - 已取消
 */
public enum RenderJobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
}
