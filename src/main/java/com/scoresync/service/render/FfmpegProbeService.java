package com.scoresync.service.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scoresync.config.AppProperties;
import com.scoresync.domain.VideoProbeResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FFprobe探测服务
 * 使用ffprobe获取视频文件的元数据信息（分辨率、帧率、时长等）
 */
@Service
public class FfmpegProbeService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public FfmpegProbeService(AppProperties appProperties, ObjectMapper objectMapper) {
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 探测视频文件信息
     *
     * @param source 视频文件路径
     * @return 视频探测结果（宽、高、时长、帧率、是否有音频）
     */
    public VideoProbeResult probe(Path source) {
        List<String> args = List.of(
                appProperties.getRender().getFfprobePath(), "-v", "error",
                "-show_entries", "stream=codec_type,width,height,r_frame_rate",
                "-show_entries", "format=duration",
                "-of", "json",
                source.toString());

        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 ffprobe，请检查配置: " + appProperties.getRender().getFfprobePath(), e);
        }

        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffprobe 超时");
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取 ffprobe 输出失败", e);
        }

        if (process.exitValue() != 0) {
            throw new IllegalStateException("ffprobe 执行失败: " + output);
        }

        try {
            return parse(output);
        } catch (Exception e) {
            throw new IllegalStateException("解析 ffprobe 输出失败: " + output, e);
        }
    }

    /**
     * 解析ffprobe的JSON输出
     */
    private VideoProbeResult parse(String output) throws Exception {
        JsonNode root = objectMapper.readTree(output);
        JsonNode streams = root.path("streams");
        JsonNode videoStream = null;
        boolean hasAudio = false;

        for (JsonNode stream : streams) {
            String codecType = stream.path("codec_type").asText();
            if ("video".equals(codecType) && videoStream == null) {
                videoStream = stream;
            } else if ("audio".equals(codecType)) {
                hasAudio = true;
            }
        }

        if (videoStream == null) {
            throw new IllegalStateException("源视频中未找到视频流");
        }

        int width = videoStream.path("width").asInt();
        int height = videoStream.path("height").asInt();
        String frameRate = videoStream.path("r_frame_rate").asText();
        double durationSeconds = root.path("format").path("duration").asDouble(0);

        if (width <= 0 || height <= 0 || durationSeconds <= 0) {
            throw new IllegalStateException("源视频元数据无效: width=" + width + ", height=" + height + ", duration=" + durationSeconds);
        }

        long durationMs = Math.round(durationSeconds * 1000);
        return new VideoProbeResult(width, height, durationMs, frameRate, hasAudio);
    }
}
