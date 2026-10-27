package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.*;
import com.scoresync.repository.ClipProjectRepository;
import com.scoresync.repository.ClipRenderJobRepository;
import com.scoresync.repository.ClipSegmentRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.service.ClipSourceValidator;
import com.scoresync.service.VideoLibraryService;
import com.scoresync.support.DomainValidationException;
import com.scoresync.support.RenderJobConflictException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 剪辑导出任务服务
 * 管理剪辑项目的导出任务：冻结保留区间、精确拼接保留片段、登记新视频资产
 * <p>
 * 与记分导出共用 {@link ExportOccupancyService} 的占用检查，两者互相冲突
 */
@Service
@Order(21)
public class ClipRenderJobService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ClipRenderJobService.class);

    private final ClipRenderJobRepository repository;
    private final ClipProjectRepository projectRepository;
    private final ClipSegmentRepository segmentRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final ClipSourceValidator sourceValidator;
    private final ClipFrameIndexService frameIndexService;
    private final ClipFfmpegCommandFactory commandFactory;
    private final FfmpegRenderService renderService;
    private final FfmpegProbeService probeService;
    private final VideoLibraryService videoLibraryService;
    private final ExportOccupancyService exportOccupancyService;
    private final AppProperties appProperties;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clip-render-job-worker");
        t.setDaemon(true);
        return t;
    });

    public ClipRenderJobService(ClipRenderJobRepository repository,
                                ClipProjectRepository projectRepository,
                                ClipSegmentRepository segmentRepository,
                                VideoAssetRepository videoAssetRepository,
                                ClipSourceValidator sourceValidator,
                                ClipFrameIndexService frameIndexService,
                                ClipFfmpegCommandFactory commandFactory,
                                FfmpegRenderService renderService,
                                FfmpegProbeService probeService,
                                VideoLibraryService videoLibraryService,
                                ExportOccupancyService exportOccupancyService,
                                AppProperties appProperties) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.segmentRepository = segmentRepository;
        this.videoAssetRepository = videoAssetRepository;
        this.sourceValidator = sourceValidator;
        this.frameIndexService = frameIndexService;
        this.commandFactory = commandFactory;
        this.renderService = renderService;
        this.probeService = probeService;
        this.videoLibraryService = videoLibraryService;
        this.exportOccupancyService = exportOccupancyService;
        this.appProperties = appProperties;
    }

    /**
     * 启动剪辑导出任务
     *
     * @param projectId 剪辑项目 ID
     * @return 创建的导出任务
     * @throws RenderJobConflictException 已有导出任务在执行时抛出
     */
    public ClipRenderJob start(long projectId) {
        return exportOccupancyService.runExclusively("CLIP_RENDER_JOB_ACTIVE", "已有导出任务正在执行", () -> {
            ClipProject project = projectRepository.findById(projectId);
            // 冻结已存档的保留区间，导出过程中的后续编辑不影响本次任务
            List<ClipSegment> keepSegments = segmentRepository.findByProjectId(projectId).stream()
                    .filter(segment -> segment.type() == ClipSegmentType.KEEP)
                    .toList();
            if (keepSegments.isEmpty()) {
                throw new DomainValidationException("没有保留任何片段，无法导出");
            }
            ClipFrameIndex index = frameIndexService.requireIndex(project);

            long jobId = repository.insert(project.id());
            ClipRenderJob job = repository.findById(jobId);
            executor.submit(() -> runJob(job.id(), keepSegments, index));
            return job;
        });
    }

    /**
     * 取消导出任务
     */
    public ClipRenderJob cancel(long jobId) {
        ClipRenderJob job = repository.findById(jobId);
        if (job.status() != ClipRenderJobStatus.QUEUED && job.status() != ClipRenderJobStatus.RUNNING) {
            throw new RenderJobConflictException("RENDER_JOB_NOT_CANCELLABLE", "任务已结束，不能取消");
        }
        if (job.status() == ClipRenderJobStatus.RUNNING) {
            renderService.cancel(jobId);
        }
        repository.update(new ClipRenderJob(job.id(), job.projectId(), ClipRenderJobStatus.CANCELLED,
                job.progressPercent(), job.outputPath(), job.outputAssetId(), job.failureMessage(),
                job.createdAt(), job.startedAt(), Instant.now()));
        deleteWorkDir(jobId);
        return repository.findById(jobId);
    }

    /**
     * 获取任务详情
     */
    public ClipRenderJob get(long jobId) {
        return repository.findById(jobId);
    }

    /**
     * 查询当前活跃任务
     */
    public Optional<ClipRenderJob> findActive() {
        return repository.findActive();
    }

    /**
     * 应用启动时清理残留的剪辑导出任务
     */
    public void failInterruptedJobs() {
        String message = "应用在导出期间停止，任务未完成";
        for (ClipRenderJob job : repository.findByStatus(ClipRenderJobStatus.QUEUED)) {
            fail(job, message);
        }
        for (ClipRenderJob job : repository.findByStatus(ClipRenderJobStatus.RUNNING)) {
            fail(job, message);
        }
    }

    /**
     * 执行导出任务
     */
    private void runJob(long jobId, List<ClipSegment> keepSegments, ClipFrameIndex index) {
        ClipRenderJob job = repository.findById(jobId);
        Path finalOutput = null;
        try {
            markRunning(jobId);
            ClipProject project = projectRepository.findById(job.projectId());
            VideoAsset video = sourceValidator.requireUnchangedSource(project);
            VideoProbeResult probe = probeService.probe(Path.of(video.absolutePath()));

            Path workDir = Paths.get(appProperties.getClip().getWorkDir(), String.valueOf(jobId));
            Files.createDirectories(workDir);

            ClipExportPlan plan = new ClipExportPlan(video.absolutePath(), keepSegments, index, probe.hasAudio());
            FfmpegCommand command = commandFactory.create(plan, workDir, finalOutput);

            // 每次导出生成独立文件名，重复导出不会覆盖既有结果
            String fileName = project.name() + "-剪辑-" + job.id() + ".mp4";
            finalOutput = Paths.get(appProperties.getRender().getOutputDir(), fileName);

            int[] lastLogged = {0};
            long durationMs = Math.max(1L, plan.totalDurationUs() / 1000L);
            renderService.execute(jobId, workDir, durationMs, command, finalOutput, percent -> {
                updateProgress(jobId, percent);
                if (percent / 10 > lastLogged[0]) {
                    lastLogged[0] = percent / 10;
                    log.info("剪辑导出进度: {}%", percent);
                }
            });

            // 校验导出结果，确认文件有效后才登记回视频库
            verifyOutput(finalOutput);
            VideoAsset registered = videoLibraryService.registerExported(finalOutput);

            repository.update(new ClipRenderJob(jobId, job.projectId(), ClipRenderJobStatus.SUCCEEDED,
                    100, finalOutput.toString(), registered.id(), null,
                    job.createdAt(), Instant.now(), Instant.now()));
        } catch (Exception e) {
            log.warn("剪辑导出任务 {} 失败: {}", jobId, e.getMessage());
            ClipRenderJob current = repository.findById(jobId);
            String message = e.getMessage() == null ? "导出失败" : e.getMessage();
            if (message.length() > 200) {
                message = message.substring(0, 200);
            }
            repository.update(new ClipRenderJob(jobId, current.projectId(), ClipRenderJobStatus.FAILED,
                    current.progressPercent(), null, null, message,
                    current.createdAt(), current.startedAt(), Instant.now()));
            deleteWorkDir(jobId);
            // 失败时不留下未登记的半成品
            deleteOutput(finalOutput);
        }
    }

    /**
     * 校验导出文件确实有效
     */
    private void verifyOutput(Path output) {
        if (output == null || !Files.isRegularFile(output)) {
            throw new IllegalStateException("导出未生成有效文件");
        }
        try {
            if (Files.size(output) <= 0) {
                throw new IllegalStateException("导出文件为空");
            }
        } catch (IOException e) {
            throw new IllegalStateException("导出文件不可读取");
        }
        VideoProbeResult probe = probeService.probe(output);
        if (probe.durationMs() <= 0) {
            throw new IllegalStateException("导出文件时长无效");
        }
    }

    private void markRunning(long jobId) {
        ClipRenderJob job = repository.findById(jobId);
        repository.update(new ClipRenderJob(jobId, job.projectId(), ClipRenderJobStatus.RUNNING,
                0, job.outputPath(), job.outputAssetId(), job.failureMessage(),
                job.createdAt(), Instant.now(), job.finishedAt()));
    }

    private void updateProgress(long jobId, int percent) {
        ClipRenderJob job = repository.findById(jobId);
        repository.update(new ClipRenderJob(jobId, job.projectId(), ClipRenderJobStatus.RUNNING,
                percent, job.outputPath(), job.outputAssetId(), job.failureMessage(),
                job.createdAt(), job.startedAt(), job.finishedAt()));
    }

    private void fail(ClipRenderJob job, String message) {
        repository.update(new ClipRenderJob(job.id(), job.projectId(), ClipRenderJobStatus.FAILED,
                job.progressPercent(), job.outputPath(), job.outputAssetId(), message,
                job.createdAt(), job.startedAt(), Instant.now()));
        deleteWorkDir(job.id());
    }

    /**
     * 删除导出工作目录
     */
    private void deleteWorkDir(long jobId) {
        Path workDir = Paths.get(appProperties.getClip().getWorkDir(), String.valueOf(jobId));
        deleteRecursively(workDir);
    }

    /**
     * 删除未登记的导出文件
     */
    private void deleteOutput(Path output) {
        if (output == null) {
            return;
        }
        try {
            Files.deleteIfExists(output);
        } catch (IOException ignored) {
        }
    }

    private void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    @Override
    public void run(String... args) {
        failInterruptedJobs();
    }

    /**
     * 关闭服务时取消运行中的导出任务
     */
    @PreDestroy
    public void shutdown() {
        renderService.cancelAll();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
