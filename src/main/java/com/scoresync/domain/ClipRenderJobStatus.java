package com.scoresync.domain;

/**
 * 剪辑导出任务状态
 * 与记分渲染任务保持一致的状态集合
 */
public enum ClipRenderJobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED
}
