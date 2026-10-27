package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.support.ToolUnavailableException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 剪辑抽帧服务
 * 调用 ffmpeg 在指定时间点抽取画面并输出 PNG
 * <p>
 * 该类只负责与 ffmpeg 交互，便于在测试中用假实现替换
 */
@Component
public class ClipFrameExtractor {

    private final AppProperties appProperties;

    public ClipFrameExtractor(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 抽取指定时间点的画面
     *
     * @param source      源视频路径
     * @param seekSeconds 跳转时间（秒）
     * @param output      输出 PNG 路径
     */
    public void extract(Path source, double seekSeconds, Path output) {
        int width = appProperties.getClip().getThumbnailWidth();
        List<String> args = new ArrayList<>(List.of(
                appProperties.getRender().getFfmpegPath(),
                "-hide_banner", "-loglevel", "error", "-y",
                "-accurate_seek",
                "-ss", String.format("%.6f", seekSeconds),
                "-i", source.toString(),
                "-an",
                "-frames:v", "1",
                "-vf", "scale=w='min(" + width + ",iw)':h=-2",
                "-update", "1",
                "-f", "image2",
                output.toString()));

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ToolUnavailableException("TOOL_UNAVAILABLE",
                    "无法启动 FFmpeg，请确认已安装 FFmpeg 或设置 SCORESYNC_FFMPEG_PATH 环境变量");
        }
        String outputText;
        try {
            outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("抽取帧超时");
            }
        } catch (IOException e) {
            throw new IllegalStateException("抽取帧失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("抽取帧被中断", e);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("抽取帧失败: " + outputText.trim());
        }
        try {
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IllegalStateException("抽取帧未生成有效图片");
            }
        } catch (IOException e) {
            throw new IllegalStateException("抽取帧未生成有效图片", e);
        }
    }
}
