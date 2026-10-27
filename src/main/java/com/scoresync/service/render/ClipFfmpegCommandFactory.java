package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipExportPlan;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.domain.ClipSegment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 剪辑导出命令工厂
 * 生成按帧序号精确选帧、按原顺序拼接保留片段的 FFmpeg 命令
 * <p>
 * 视频用 select 滤镜按解码帧序号选取，再用分段 setpts 把各段的时间轴接成连续序列；
 * 音频按帧索引给出的时间戳切取对应区间，同样重排时间轴后与视频对齐。
 * 全程重新编码，不使用关键帧 copy，也不按标称帧率换算秒数近似选帧。
 */
@Component
public class ClipFfmpegCommandFactory {

    private final AppProperties appProperties;

    public ClipFfmpegCommandFactory(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 创建剪辑导出命令
     *
     * @param plan        导出计划
     * @param workDir     工作目录
     * @param outputPath  最终输出路径
     */
    public FfmpegCommand create(ClipExportPlan plan, Path workDir, Path outputPath) {
        Path filterScript = workDir.resolve("clip-filter.txt");
        Path temporaryOutput = workDir.resolve("clip-output.mp4");

        writeFilterScript(filterScript, plan);

        List<String> args = new ArrayList<>();
        args.add(appProperties.getRender().getFfmpegPath());
        args.add("-hide_banner");
        args.add("-y");
        args.add("-i");
        args.add(plan.videoAbsolutePath());
        args.add("-filter_complex_script");
        args.add(filterScript.toString());
        args.add("-map");
        args.add("[vout]");
        if (plan.hasAudio()) {
            args.add("-map");
            args.add("[aout]");
        } else {
            args.add("-an");
        }
        args.add("-map_metadata");
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
        if (plan.hasAudio()) {
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add(appProperties.getRender().getAudioBitrate());
        }
        args.add("-movflags");
        args.add("+faststart");
        args.add("-progress");
        args.add("pipe:1");
        args.add("-nostats");
        args.add(temporaryOutput.toString());

        return new FfmpegCommand(args, filterScript, temporaryOutput);
    }

    /**
     * 生成滤镜脚本
     */
    private void writeFilterScript(Path filterScript, ClipExportPlan plan) {
        List<ClipSegment> segments = plan.segments();
        ClipFrameIndex index = plan.frameIndex();

        StringBuilder sb = new StringBuilder();

        // 视频：按解码帧序号精确选取保留帧
        sb.append("[0:v]select='").append(frameSelection(segments)).append("'");
        sb.append(",setpts='").append(piecewiseSetpts(index, segments)).append("'");
        sb.append("[vout]");

        if (plan.hasAudio()) {
            sb.append(";");
            // 音频：按帧索引给出的时间戳切取对应区间
            sb.append("[0:a]aselect='").append(timeSelection(index, segments)).append("'");
            sb.append(",asetpts='").append(piecewiseSetpts(index, segments)).append("'");
            sb.append("[aout]");
        }
        sb.append("\n");

        writeFile(filterScript, sb.toString());
    }

    /**
     * 帧选择表达式
     * 例如 between(n\,10\,19)+between(n\,40\,49)
     */
    String frameSelection(List<ClipSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (ClipSegment segment : segments) {
            if (sb.length() > 0) {
                sb.append("+");
            }
            sb.append("between(n\\,")
                    .append(segment.startFrame())
                    .append("\\,")
                    .append(segment.endFrame() - 1)
                    .append(")");
        }
        return sb.toString();
    }

    /**
     * 音频时间区间选择表达式
     * 例如 between(t\,0.333333\,0.633333)+between(t\,1.333333\,1.633333)
     */
    String timeSelection(ClipFrameIndex index, List<ClipSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (ClipSegment segment : segments) {
            if (sb.length() > 0) {
                sb.append("+");
            }
            sb.append("between(t\\,")
                    .append(seconds(index.ptsUs(segment.startFrame())))
                    .append("\\,")
                    .append(seconds(index.endTimeUs(segment.endFrame())))
                    .append(")");
        }
        return sb.toString();
    }

    /**
     * 分段时间轴重排表达式
     * <p>
     * 第一段整体前移到零点；此后每一段额外前移的量等于
     * “该段起点与前一段终点之间的空白”，
     * 使各段在输出中首尾相接且保持段内原有的帧间隔（兼容可变帧率）。
     * 表达式中的时间需除以时间基，因为 setpts 的 PTS 以时间基为单位。
     */
    String piecewiseSetpts(ClipFrameIndex index, List<ClipSegment> segments) {
        StringBuilder sb = new StringBuilder();
        StringBuilder shift = new StringBuilder();

        long firstStart = index.ptsUs(segments.get(0).startFrame());
        sb.append("PTS+(").append(seconds(-firstStart)).append(")/TB");

        long previousEnd = index.endTimeUs(segments.get(0).endFrame());
        for (int i = 1; i < segments.size(); i++) {
            ClipSegment segment = segments.get(i);
            long start = index.ptsUs(segment.startFrame());
            // 该段需要额外前移的空白量
            long gap = previousEnd - start;
            shift.append("+(")
                    .append(seconds(gap))
                    .append(")/TB*gte(PTS\\,")
                    .append(seconds(start))
                    .append("/TB)");
            previousEnd = index.endTimeUs(segment.endFrame());
        }
        sb.append(shift);
        return sb.toString();
    }

    /**
     * 秒数格式化，保留六位小数（微秒精度）
     */
    String seconds(long micros) {
        return String.format("%.6f", micros / 1_000_000d);
    }

    private void writeFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入滤镜脚本: " + file, e);
        }
    }
}
