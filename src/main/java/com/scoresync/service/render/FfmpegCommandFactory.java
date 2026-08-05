package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.RenderPlan;
import com.scoresync.domain.VideoProbeResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * FFmpeg命令工厂
 * 根据渲染计划生成FFmpeg命令行参数和滤镜脚本
 */
@Component
public class FfmpegCommandFactory {

    private final AppProperties appProperties;

    public FfmpegCommandFactory(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 创建FFmpeg命令
     *
     * @param plan    渲染计划
     * @param workDir 工作目录
     * @return FFmpeg命令对象
     */
    public FfmpegCommand create(RenderPlan plan, Path workDir) {
        VideoProbeResult probe = plan.probe();
        int frameWidth = probe.width();
        int frameHeight = probe.height();

        int margin = (int) Math.round(frameWidth * 0.025);
        int overlayX = margin;
        int overlayHeight = RenderDimensions.barHeight(frameWidth, frameHeight);
        int overlayY = frameHeight - margin - overlayHeight;

        List<RenderPlan.Segment> segments = plan.segments();
        Path filterScript = workDir.resolve("overlay-filter.txt");
        Path temporaryOutput = workDir.resolve("output.mp4");

        writeFilterScript(filterScript, segments, overlayX, overlayY);

        List<String> args = new ArrayList<>();
        args.add(appProperties.getRender().getFfmpegPath());
        args.add("-hide_banner");
        args.add("-y");
        args.add("-i");
        args.add(plan.video().absolutePath());

        String frameRate = probe.frameRate();
        for (RenderPlan.Segment segment : segments) {
            args.add("-loop");
            args.add("1");
            args.add("-framerate");
            args.add(frameRate);
            args.add("-i");
            args.add(segment.pngPath().toString());
        }

        args.add("-filter_complex_script");
        args.add(filterScript.toString());
        args.add("-map");
        args.add("[scoreboard]");
        args.add("-map");
        args.add("0:a?");
        args.add("-map_metadata");
        args.add("0");
        args.add("-map_chapters");
        args.add("0");

        args.add("-c:v");
        args.add("libx264");
        args.add("-preset");
        args.add(appProperties.getRender().getVideoPreset());
        args.add("-crf");
        args.add(String.valueOf(appProperties.getRender().getVideoCrf()));
        args.add("-pix_fmt");
        args.add("yuv420p");
        args.add("-fps_mode");
        args.add("passthrough");
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add(appProperties.getRender().getAudioBitrate());
        args.add("-movflags");
        args.add("+faststart");
        args.add("-t");
        args.add(seconds(probe.durationMs()));
        args.add("-progress");
        args.add("pipe:1");
        args.add("-nostats");
        args.add(temporaryOutput.toString());

        return new FfmpegCommand(args, filterScript, temporaryOutput);
    }

    /**
     * 生成FFmpeg滤镜脚本
     * 使用overlay滤镜在指定时间段切换不同的计分板图片
     */
    private void writeFilterScript(Path filterScript, List<RenderPlan.Segment> segments,
                                   int overlayX, int overlayY) {
        StringBuilder sb = new StringBuilder();
        int count = segments.size();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(";\n");
            }
            String previousLabel = i == 0 ? "0:v" : "v" + i;
            int pngInput = i + 1;
            RenderPlan.Segment segment = segments.get(i);

            String startSeconds = seconds(segment.startTimeMs());
            String nextLabel = (i == count - 1) ? "scoreboard" : "v" + (i + 1);

            String enable;
            if (i == count - 1) {
                enable = "gte(t," + startSeconds + ")";
            } else {
                String endSeconds = seconds(segment.endTimeMs());
                enable = "gte(t," + startSeconds + ")*lt(t," + endSeconds + ")";
            }

            sb.append("[").append(previousLabel).append("][")
                    .append(pngInput).append(":v]overlay=x=")
                    .append(overlayX).append(":y=").append(overlayY)
                    .append(":enable='").append(enable).append("'")
                    .append("[").append(nextLabel).append("]");
        }
        sb.append("\n");

        try {
            Files.createDirectories(filterScript.getParent());
            Files.writeString(filterScript, sb.toString());
        } catch (IOException e) {
            throw new IllegalStateException("无法写入滤镜脚本: " + filterScript, e);
        }
    }

    private String seconds(long ms) {
        return String.format("%.3f", ms / 1000.0);
    }
}
