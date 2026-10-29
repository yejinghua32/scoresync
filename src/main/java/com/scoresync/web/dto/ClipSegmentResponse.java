package com.scoresync.web.dto;

import com.scoresync.domain.ClipSegment;

/**
 * 剪辑片段响应体
 * 包含片段的帧区间、精选状态以及在源视频中的时间位置（帧索引就绪后才有值）
 */
public record ClipSegmentResponse(long id, String type, int startFrame, int endFrame, boolean featured,
                                  Long startTimeMs, Long endTimeMs) {

    public static ClipSegmentResponse from(ClipSegment segment) {
        return new ClipSegmentResponse(segment.id(), segment.type().name(),
                segment.startFrame(), segment.endFrame(), segment.featured(), null, null);
    }
}
