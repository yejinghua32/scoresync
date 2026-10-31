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
 * 生成按帧索引给出的时间区间精确选帧、按原时间顺序拼接保留片段的 FFmpeg 命令
 * <p>
 * 视频与音频都按帧索引换算出的时间区间选取（区间即保留片段在源视频中的时间范围，
 * 不按平均帧率换算），再用统一的分段 setpts 把各段时间轴接成连续序列：
 * 每一帧按自身时间戳落入的区间得到固定平移量，与帧到达滤镜的顺序无关，
 * 因此对含 B 帧或可变帧率的源视频也能保证选帧正确、时间轴连续。
 * 全程重新编码，不使用关键帧 copy。
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
        ClipFrameIndex index = plan.frameIndex();
        List<long[]> windows = windows(index, plan.segments());
        String selection = timeSelection(windows);
        String setpts = piecewiseSetpts(windows);

        StringBuilder sb = new StringBuilder();

        // 视频：按时间区间精确选取保留帧
        sb.append("[0:v]select='").append(selection).append("'");
        sb.append(",setpts='").append(setpts).append("'");
        sb.append("[vout]");

        if (plan.hasAudio()) {
            sb.append(";");
            // 音频：按帧索引给出的时间戳切取对应区间
            sb.append("[0:a]aselect='").append(selection).append("'");
            sb.append(",asetpts='").append(setpts).append("'");
            sb.append("[aout]");
        }
        sb.append("\n");

        writeFile(filterScript, sb.toString());
    }

    /**
     * 保留片段对应的时间区间
     * <p>
     * 每个片段是半开区间 [起点帧时间, 终点帧时间)；
     * ffprobe 逐帧输出的顺序在个别异常文件上并不能保证时间单调，
     * 因此与前一区间比较做收缩，保证区间两两不重叠，
     * 后续的按时间选取与重排对任意帧都只会得到唯一的平移量。
     *
     * @return 每个区间为 [startUs, endUs]，退化为空区间的片段会被跳过
     */
    List<long[]> windows(ClipFrameIndex index, List<ClipSegment> segments) {
        List<long[]> result = new ArrayList<>();
        long lowerBound = Long.MIN_VALUE;
        for (ClipSegment segment : segments) {
            long start = index.ptsUs(segment.startFrame());
            long end = index.endTimeUs(segment.endFrame());
            if (start < lowerBound) {
                start = lowerBound;
            }
            if (end < lowerBound) {
                end = lowerBound;
            }
            if (end <= start) {
                continue;
            }
            result.add(new long[]{start, end});
            lowerBound = end;
        }
        return result;
    }

    /**
     * 时间区间选择表达式（视频与音频共用）
     * 例如 gte(t\,0.333333)*lt(t\,0.633333)+gte(t\,1.333333)*lt(t\,1.633333)
     */
    String timeSelection(ClipFrameIndex index, List<ClipSegment> segments) {
        return timeSelection(windows(index, segments));
    }

    /**
     * 时间区间选择表达式（视频与音频共用）
     * 例如 gte(t\,0.333333)*lt(t\,0.633333)+gte(t\,1.333333)*lt(t\,1.633333)
     */
    String timeSelection(List<long[]> windows) {
        StringBuilder sb = new StringBuilder();
        for (long[] window : windows) {
            if (sb.length() > 0) {
                sb.append("+");
            }
            sb.append("gte(t\\,").append(seconds(window[0]))
                    .append(")*lt(t\\,").append(seconds(window[1])).append(")");
        }
        return sb.toString();
    }

    String piecewiseSetpts(ClipFrameIndex index, List<ClipSegment> segments) {
        return piecewiseSetpts(windows(index, segments));
    }

    /**
     * 分段时间轴重排表达式
     * <p>
     * 每一帧按自身时间戳落入的区间得到固定平移量：
     * 该段整体前移到累计拼接的位置，且段内保持原有帧间隔（兼容可变帧率）。
     * 判断条件使用 T（帧时间戳的秒数），与帧到达滤镜的顺序无关，
     * 含 B 帧的源视频也不会出现个别帧被错误归段的问题。
     * 表达式输出的时间以时间基为单位，因此秒数需除以时间基。
     */
    String piecewiseSetpts(List<long[]> windows) {
        StringBuilder sb = new StringBuilder();

        long cumulative = 0L;
        long previousShift = 0L;
        for (int i = 0; i < windows.size(); i++) {
            long start = windows.get(i)[0];
            long end = windows.get(i)[1];

            long shift = cumulative - start;
            if (i == 0) {
                sb.append("PTS+(").append(seconds(shift)).append(")/TB");
            } else {
                long step = shift - previousShift;
                if (step != 0) {
                    sb.append("+(").append(seconds(step)).append(")/TB*gte(T\\,")
                            .append(seconds(start)).append(")");
                }
            }
            previousShift = shift;
            cumulative += end - start;
        }
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
