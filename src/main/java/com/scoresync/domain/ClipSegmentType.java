package com.scoresync.domain;

/**
 * 剪辑片段类型
 * KEEP - 保留帧，导出时按原顺序拼接
 * DROP - 舍弃帧，导出时跳过
 * <p>
 * 未标记帧（既非保留也非舍弃）由片段之间的空隙派生，不单独存储
 */
public enum ClipSegmentType {
    KEEP, DROP
}
