package com.scoresync.domain;

/**
 * 剪辑片段规格
 * 服务层内部使用的片段输入，区间为左闭右开 [startFrame, endFrame)
 *
 * @param type       片段类型（保留或舍弃）
 * @param startFrame 起始帧序号（含）
 * @param endFrame   结束帧序号（不含）
 */
public record ClipSegmentSpec(ClipSegmentType type, int startFrame, int endFrame) {
}
