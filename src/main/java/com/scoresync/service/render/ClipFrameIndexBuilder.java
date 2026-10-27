package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.support.ToolUnavailableException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 剪辑帧索引构建器
 * 用 ffprobe 逐帧读取显示时间戳，构建不依赖平均帧率的帧索引
 * <p>
 * 该类只负责与 ffprobe 交互，便于在测试中用假实现替换
 */
@Component
public class ClipFrameIndexBuilder {

    private final AppProperties appProperties;

    public ClipFrameIndexBuilder(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 构建帧索引
     *
     * @param source 源视频路径
     * @return 帧索引
     */
    public ClipFrameIndex build(Path source) {
        long containerStartUs = readContainerStartUs(source);
        List<Long> pts = new ArrayList<>();
        List<Long> durations = new ArrayList<>();

        List<String> args = List.of(
                appProperties.getRender().getFfprobePath(), "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "frame=best_effort_timestamp_time,pts_time,pkt_duration_time",
                "-of", "csv=p=0",
                source.toString());

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ToolUnavailableException("TOOL_UNAVAILABLE",
                    "无法启动 ffprobe，请确认已安装 FFmpeg 或设置 SCORESYNC_FFPROBE_PATH 环境变量");
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                parseFrameRow(trimmed, pts, durations);
            }
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("建立帧索引超时，源视频可能过长");
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 ffprobe 帧信息失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("建立帧索引被中断", e);
        }

        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffprobe 读取帧信息失败");
        }
        if (pts.isEmpty()) {
            throw new IllegalStateException("源视频中未解析到任何画面帧");
        }

        long[] ptsArray = new long[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            ptsArray[i] = pts.get(i);
        }
        long[] durationArray = new long[durations.size()];
        for (int i = 0; i < durations.size(); i++) {
            durationArray[i] = durations.get(i);
        }
        return new ClipFrameIndex(ptsArray, durationArray, containerStartUs);
    }

    /**
     * 解析一行帧信息
     * 期望格式为 best_effort_timestamp_time,pts_time,pkt_duration_time
     */
    private void parseFrameRow(String row, List<Long> pts, List<Long> durations) {
        String[] parts = row.split(",");
        Long timestamp = null;
        for (int i = 0; i < parts.length && i < 2; i++) {
            timestamp = parseSeconds(parts[i]);
            if (timestamp != null) {
                break;
            }
        }
        if (timestamp == null) {
            // 时间戳缺失时按上一帧加一帧的默认间隔推算，保证帧序号连续
            long fallback = pts.isEmpty() ? 0L : pts.get(pts.size() - 1) + 33_333L;
            pts.add(fallback);
            durations.add(0L);
            return;
        }
        pts.add(timestamp);
        Long duration = parts.length > 2 ? parseSeconds(parts[2]) : null;
        durations.add(duration == null ? 0L : duration);
    }

    /**
     * 把秒转换成微秒，无法解析时返回 null
     */
    private Long parseSeconds(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "N/A".equalsIgnoreCase(trimmed)) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(trimmed) * 1_000_000d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 读取容器起始时间（微秒）
     */
    private long readContainerStartUs(Path source) {
        List<String> args = List.of(
                appProperties.getRender().getFfprobePath(), "-v", "error",
                "-show_entries", "format=start_time",
                "-of", "default=noprint_wrappers=1:nokey=1",
                source.toString());
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(60, TimeUnit.SECONDS);
            Long value = parseSeconds(output.trim());
            return value == null ? 0L : value;
        } catch (IOException e) {
            throw new ToolUnavailableException("TOOL_UNAVAILABLE",
                    "无法启动 ffprobe，请确认已安装 FFmpeg 或设置 SCORESYNC_FFPROBE_PATH 环境变量");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取容器信息被中断", e);
        }
    }
}
