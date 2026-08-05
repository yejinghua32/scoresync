package com.scoresync.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * 渲染计划
 * 定义完整渲染任务的所有参数，包括项目、视频、模板及分段信息
 *
 * @param project  比赛项目
 * @param video    视频资产
 * @param template 比分牌模板
 * @param segments 渲染分段列表
 * @param probe    视频探测结果
 * @param workDir  工作目录
 */
public record RenderPlan(MatchProject project, VideoAsset video, ScoreboardTemplate template,
                         List<Segment> segments, VideoProbeResult probe, Path workDir) {

    /**
     * 渲染分段
     * 表示一个需要渲染的时间段及其对应输出 PNG 路径
     *
     * @param startTimeMs 开始时间（毫秒）
     * @param endTimeMs   结束时间（毫秒）
     * @param pngPath     生成的 PNG 图片路径
     */
    public record Segment(long startTimeMs, long endTimeMs, Path pngPath) {
    }
}
