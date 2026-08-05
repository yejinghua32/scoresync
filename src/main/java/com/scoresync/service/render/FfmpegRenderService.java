package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.RenderPlan;
import com.scoresync.domain.VideoProbeResult;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * FFmpeg渲染服务
 * 负责执行FFmpeg命令进行视频渲染，跟踪进度并管理进程生命周期
 */
@Service
public class FfmpegRenderService {

    private final AppProperties appProperties;
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();

    public FfmpegRenderService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 执行渲染任务
     *
     * @param jobId            任务ID
     * @param plan             渲染计划
     * @param command          FFmpeg命令
     * @param finalOutput      最终输出路径
     * @param progressCallback 进度回调
     */
    public void execute(long jobId, RenderPlan plan, FfmpegCommand command,
                        Path finalOutput, IntConsumer progressCallback) {
        VideoProbeResult probe = plan.probe();

        ProcessBuilder builder = new ProcessBuilder(command.arguments());
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 FFmpeg: " + appProperties.getRender().getFfmpegPath(), e);
        }
        runningProcesses.put(jobId, process);

        Path logFile = plan.workDir().resolve("ffmpeg.log");
        try {
            writeStreamToFile(process.getErrorStream(), logFile);
            readProgress(process, probe.durationMs(), progressCallback);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("FFmpeg 退出码: " + exitCode);
            }
            if (!Files.exists(command.temporaryOutput()) || Files.size(command.temporaryOutput()) <= 0) {
                throw new IllegalStateException("FFmpeg 未生成有效输出文件");
            }
            Files.createDirectories(finalOutput.getParent());
            Files.move(command.temporaryOutput(), finalOutput, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("渲染被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("渲染失败: " + e.getMessage(), e);
        } finally {
            runningProcesses.remove(jobId);
        }
    }

    /**
     * 取消指定任务
     */
    public void cancel(long jobId) {
        Process process = runningProcesses.get(jobId);
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /**
     * 取消所有运行中的任务
     */
    public void cancelAll() {
        runningProcesses.values().forEach(process -> {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        });
        runningProcesses.clear();
    }

    /**
     * 清理孤立进程
     * 查找并终止工作目录下残留的FFmpeg进程
     *
     * @return 清理的进程数量
     */
    public int killOrphanedProcesses() {
        String workDir = appProperties.getRender().getWorkDir();
        int[] killed = {0};
        ProcessHandle.allProcesses()
                .filter(p -> {
                    String commandLine = p.info().commandLine().orElse("");
                    if (!commandLine.replace('\\', '/').toLowerCase().contains("ffmpeg")) {
                        return false;
                    }
                    return commandLine.replace('\\', '/').contains(workDir.replace('\\', '/'));
                })
                .forEach(p -> {
                    try {
                        p.destroyForcibly();
                        killed[0]++;
                    } catch (Exception ignored) {
                    }
                });
        return killed[0];
    }

    /**
     * 从FFmpeg输出读取进度
     */
    private void readProgress(Process process, long durationMs, IntConsumer progressCallback) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("out_time_us=")) {
                    try {
                        long outTimeUs = Long.parseLong(line.substring("out_time_us=".length()).trim());
                        progressCallback.accept(progressPercent(outTimeUs, durationMs));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }

    /**
     * 根据FFmpeg输出的时间计算进度百分比
     */
    static int progressPercent(long outTimeUs, long durationMs) {
        long durationUs = durationMs * 1000L;
        if (durationUs <= 0) {
            return 0;
        }
        int percent = (int) (outTimeUs * 100L / durationUs);
        if (percent < 0) {
            return 0;
        }
        return Math.min(99, percent);
    }

    /**
     * 将输入流写入日志文件
     */
    private void writeStreamToFile(java.io.InputStream stream, Path file) {
        new Thread(() -> {
            try (java.io.OutputStream out = Files.newOutputStream(file);
                 java.io.BufferedWriter writer = new java.io.BufferedWriter(
                         new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = stream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            } catch (IOException ignored) {
            }
        }).start();
    }
}
