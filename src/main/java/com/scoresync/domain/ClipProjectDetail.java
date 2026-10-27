package com.scoresync.domain;

import java.util.List;

/**
 * 剪辑项目详情
 * 项目主数据、源视频信息与已存档的片段
 *
 * @param project  项目主数据
 * @param video    源视频资产
 * @param segments 已存档片段（按起始帧升序）
 */
public record ClipProjectDetail(ClipProject project, VideoAsset video, List<ClipSegment> segments) {
}
