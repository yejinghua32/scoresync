package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.RenderPlan;
import com.scoresync.domain.VideoProbeResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * FFmpeg渲染服务
 * 负责执行FFmpeg命令进行视频渲染，跟踪进度并管理进程生命周期
 */
@Service
public class FfmpegRenderService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRenderService.class);
    private static final int STDERR_TAIL_BYTES = 64 * 1024;

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
        execute(jobId, plan.workDir(), plan.probe().durationMs(), command, finalOutput, progressCallback);
    }

    /**
     * 执行渲染任务
     * 与渲染计划无关的通用入口，剪辑导出同样使用
     *
     * @param jobId            任务ID
     * @param workDir          工作目录
     * @param durationMs       输出时长（用于计算进度百分比）
     * @param command          FFmpeg命令
     * @param finalOutput      最终输出路径
     * @param progressCallback 进度回调
     */
    public void execute(long jobId, Path workDir, long durationMs, FfmpegCommand command,
                        Path finalOutput, IntConsumer progressCallback) {
        ProcessBuilder builder = new ProcessBuilder(command.arguments());
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("无法启动 FFmpeg: " + appProperties.getRender().getFfmpegPath(), e);
        }
        runningProcesses.put(jobId, process);

        Path logFile = workDir.resolve("ffmpeg.log");
        StderrDrain drain = startStderrDrain(process.getErrorStream(), logFile);
        try {
            Thread stdoutDrain = startStdoutDrain(process.getInputStream(), durationMs, progressCallback);
            int exitCode = process.waitFor();
            drain.await();
            stdoutDrain.join();
            String stderrTail = drain.tail();
            if (exitCode != 0) {
                String tail = stderrTail.length() > 4000
                        ? stderrTail.substring(stderrTail.length() - 4000)
                        : stderrTail;
                log.error("FFmpeg 导出失败: jobId={}, type={}, exitCode={}, encodingThreads={}, "
                                + "filterThreads={}, logFile={}, stderrTail={}",
                        jobId, taskType(command), exitCode,
                        appProperties.getRender().getMaxEncodingThreads(),
                        appProperties.getRender().getMaxFilterThreads(), logFile, tail);
                throw new IllegalStateException(describeExitCode(exitCode));
            }
            if (!Files.exists(command.temporaryOutput()) || Files.size(command.temporaryOutput()) <= 0) {
                throw new IllegalStateException("FFmpeg 未生成有效输出文件");
            }
            Files.createDirectories(finalOutput.getParent());
            Files.move(command.temporaryOutput(), finalOutput, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            // 先清空两条管道，再等待进程真正退出，避免取消后残留子进程
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            boolean stopped = false;
            while (!stopped) {
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                    stopped = !process.isAlive();
                } catch (InterruptedException ignored) {
                    process.destroyForcibly();
                }
            }
            drain.await();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("渲染被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("渲染失败: " + e.getMessage(), e);
        } finally {
            drain.await();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            runningProcesses.remove(jobId);
        }
    }

    /**
     * 将 FFmpeg 退出码转换为用户可理解的中文错误。
     */
    static String describeExitCode(int exitCode) {
        if (exitCode == -12) {
            return "FFmpeg 内存或系统资源不足（退出码 -12）";
        }
        return "FFmpeg 导出失败（退出码 " + exitCode + "）";
    }

    /**
     * 从命令输出文件名判定任务类型，仅用于失败日志检索。
     */
    static String taskType(FfmpegCommand command) {
        String name = command.temporaryOutput().getFileName().toString();
        return name.startsWith("clip-") ? "CLIP_RENDER" : "SCORE_RENDER";
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

    @PreDestroy
    public void shutdown() {
        cancelAll();
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
        int killed = killOrphanedProcessesIn(appProperties.getRender().getWorkDir());
        // 剪辑导出使用独立工作目录，需要一并清理
        killed += killOrphanedProcessesIn(appProperties.getClip().getWorkDir());
        return killed;
    }

    /**
     * 清理指定工作目录下的残留 FFmpeg 进程
     */
    private int killOrphanedProcessesIn(String workDir) {
        if (workDir == null || workDir.isBlank()) {
            return 0;
        }
        String normalizedWorkDir = workDir.replace('\\', '/');
        int[] killed = {0};
        ProcessHandle.allProcesses()
                .filter(p -> {
                    String commandLine = p.info().commandLine().orElse("");
                    if (!commandLine.replace('\\', '/').toLowerCase().contains("ffmpeg")) {
                        return false;
                    }
                    return commandLine.replace('\\', '/').contains(normalizedWorkDir);
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
     * 在独立线程读取进度，避免 stderr drain 等待进程退出时 stdout 管道被写满。
     */
    private Thread startStdoutDrain(InputStream stream, long durationMs, IntConsumer progressCallback) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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
            } catch (IOException e) {
                log.warn("读取 FFmpeg 进度输出失败: {}", e.getMessage());
            }
        }, "ffmpeg-stdout-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
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
     * 持续读取 stderr，完整写入日志，并在内存中仅保留有界尾部。
     * 返回的 drain 可等待，确保进程退出后日志和尾部均已收齐。
     */
    private StderrDrain startStderrDrain(InputStream stream, Path file) {
        StderrDrain drain = new StderrDrain(stream, file, STDERR_TAIL_BYTES);
        Thread thread = new Thread(drain, "ffmpeg-stderr-drain");
        thread.setDaemon(true);
        thread.start();
        return drain;
    }

    private static final class StderrDrain implements Runnable {
        private final InputStream input;
        private final Path file;
        private final int maxTailBytes;
        private final byte[] tail;
        private int tailLength;
        private boolean completed;

        private StderrDrain(InputStream input, Path file, int maxTailBytes) {
            this.input = input;
            this.file = file;
            this.maxTailBytes = maxTailBytes;
            this.tail = new byte[maxTailBytes];
        }

        @Override
        public void run() {
            try {
                Files.createDirectories(file.getParent());
                try (InputStream source = input;
                     OutputStream out = Files.newOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = source.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        appendTail(buffer, len);
                    }
                }
            } catch (IOException e) {
                log.warn("读取 FFmpeg stderr 失败: {}", e.getMessage());
            } finally {
                synchronized (this) {
                    completed = true;
                    notifyAll();
                }
            }
        }

        private synchronized void appendTail(byte[] bytes, int length) {
            if (length >= maxTailBytes) {
                System.arraycopy(bytes, length - maxTailBytes, tail, 0, maxTailBytes);
                tailLength = maxTailBytes;
                return;
            }
            if (tailLength + length > maxTailBytes) {
                int remove = tailLength + length - maxTailBytes;
                System.arraycopy(tail, remove, tail, 0, tailLength - remove);
                tailLength -= remove;
            }
            System.arraycopy(bytes, 0, tail, tailLength, length);
            tailLength += length;
        }

        synchronized void await() {
            boolean interrupted = false;
            while (!completed) {
                try {
                    wait(100);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized String tail() {
            return new String(tail, 0, tailLength, StandardCharsets.UTF_8);
        }
    }
}
