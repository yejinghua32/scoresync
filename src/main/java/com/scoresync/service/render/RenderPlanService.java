package com.scoresync.service.render;

import com.scoresync.domain.*;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 渲染计划服务
 * 负责生成渲染计划，将视频按计分板变化点分段并预渲染每段的计分板图片
 */
@Service
public class RenderPlanService {

    private final Map<ScoreboardTemplate, ScoreboardTemplateRenderer> renderers;
    private final ScoreboardSnapshotService snapshotService;

    public RenderPlanService(List<ScoreboardTemplateRenderer> renderers,
                             ScoreboardSnapshotService snapshotService) {
        this.renderers = renderers.stream()
                .collect(Collectors.toMap(ScoreboardTemplateRenderer::template, Function.identity()));
        this.snapshotService = snapshotService;
    }

    /**
     * 创建渲染计划
     * 根据计分板cue生成若干渲染分段，每个分段对应一个PNG图片
     *
     * @param project 比赛项目
     * @param video   视频资源
     * @param cues    计分板cue列表
     * @param probe   视频探测结果
     * @param workDir 工作目录，用于存放分段图片
     * @return 渲染计划
     */
    public RenderPlan create(MatchProject project, VideoAsset video, List<ScoreboardCue> cues,
                             VideoProbeResult probe, Path workDir) {
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建工作目录: " + workDir, e);
        }

        ScoreboardTemplateRenderer renderer = renderers.get(project.scoreboardTemplate());
        if (renderer == null) {
            throw new IllegalStateException("不支持的模板: " + project.scoreboardTemplate());
        }

        List<RenderPlan.Segment> segments = new ArrayList<>();
        long cursor = 0;
        int index = 0;
        for (long boundary : boundaries(cues, probe.durationMs())) {
            if (boundary == cursor) {
                continue;
            }
            long end = boundary;
            if (cursor >= probe.durationMs()) {
                break;
            }
            Path png = workDir.resolve(String.format("segment-%04d.png", index++));
            renderSegment(project, cues, renderer, probe, cursor, png);
            segments.add(new RenderPlan.Segment(cursor, end, png));
            cursor = boundary;
        }
        if (cursor < probe.durationMs()) {
            Path png = workDir.resolve(String.format("segment-%04d.png", index++));
            renderSegment(project, cues, renderer, probe, cursor, png);
            segments.add(new RenderPlan.Segment(cursor, probe.durationMs(), png));
        }

        return new RenderPlan(project, video, project.scoreboardTemplate(), segments, probe, workDir);
    }

    /**
     * 计算计分板变化的边界时间点
     */
    private List<Long> boundaries(List<ScoreboardCue> cues, long durationMs) {
        List<Long> result = new ArrayList<>();
        for (ScoreboardCue cue : cues) {
            long t = Math.max(0, cue.startTimeMs());
            if (t > 0 && t < durationMs && !result.contains(t)) {
                result.add(t);
            }
        }
        result.sort(Long::compareTo);
        result.add(durationMs);
        return result;
    }

    /**
     * 渲染单个分段的计分板图片
     */
    private void renderSegment(MatchProject project, List<ScoreboardCue> cues,
                               ScoreboardTemplateRenderer renderer, VideoProbeResult probe,
                               long timeMs, Path png) {
        ScoreboardSnapshot snapshot = snapshotService.at(
                project, cues, timeMs, probe.width(), probe.height());
        BufferedImage image = renderer.render(snapshot);
        try {
            ImageIO.write(image, "png", png.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("无法写入分段图片: " + png, e);
        }
    }
}
