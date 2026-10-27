package com.scoresync.domain;

import java.util.List;

/**
 * 剪辑导出计划
 * 描述一次导出要保留的帧区间及其对应的时间信息
 *
 * @param videoAbsolutePath 源视频绝对路径
 * @param segments          保留片段（帧区间，按源顺序）
 * @param frameIndex         帧索引，用于把帧序号换算成时间戳
 * @param hasAudio           源视频是否含音轨
 */
public record ClipExportPlan(String videoAbsolutePath, List<ClipSegment> segments,
                              ClipFrameIndex frameIndex, boolean hasAudio) {

    /**
     * 计算保留片段的总时长（微秒）
     */
    public long totalDurationUs() {
        long total = 0;
        for (ClipSegment segment : segments) {
            total += frameIndex.endTimeUs(segment.endFrame()) - frameIndex.ptsUs(segment.startFrame());
        }
        return total;
    }
}
