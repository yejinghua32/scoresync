package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.*;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.RenderJobRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.DomainNotFoundException;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * 渲染任务服务
 * 管理视频渲染任务的创建、取消、进度跟踪
 * 整个应用同时只允许一个渲染任务运行
 */
@Service
@Order(20)
public class RenderJobService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(RenderJobService.class);

    private final RenderJobRepository repository;
    private final MatchProjectRepository projectRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final ScoreEventRepository scoreEventRepository;
    private final FfmpegProbeService probeService;
    private final RenderPlanService planService;
    private final FfmpegCommandFactory commandFactory;
    private final FfmpegRenderService renderService;
    private final AppProperties appProperties;

    private final ReentrantLock mutex = new ReentrantLock();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "render-job-worker");
        t.setDaemon(true);
        return t;
    });

    public RenderJobService(RenderJobRepository repository,
                            MatchProjectRepository projectRepository,
                            VideoAssetRepository videoAssetRepository,
                            ScoreEventRepository scoreEventRepository,
                            FfmpegProbeService probeService,
                            RenderPlanService planService,
                            FfmpegCommandFactory commandFactory,
                            FfmpegRenderService renderService,
                            AppProperties appProperties) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        this.videoAssetRepository = videoAssetRepository;
        this.scoreEventRepository = scoreEventRepository;
        this.probeService = probeService;
        this.planService = planService;
        this.commandFactory = commandFactory;
        this.renderService = renderService;
        this.appProperties = appProperties;
    }

    /**
     * 启动渲染任务
     *
     * @param matchId 比赛项目ID
     * @return 创建的渲染任务
     * @throws RenderJobConflictException 如果已有任务在运行
     */
    public RenderJob start(long matchId) {
        mutex.lock();
        try {
            if (repository.findActive().isPresent()) {
                throw new RenderJobConflictException("RENDER_JOB_ACTIVE", "已有导出任务正在执行");
            }
            var project = projectRepository.findById(matchId);
            if (project == null) {
                throw new DomainNotFoundException("比赛项目不存在");
            }
            long jobId = repository.insert(project.id(), project.scoreboardTemplate());
            RenderJob job = repository.findById(jobId);

            executor.submit(() -> runJob(job.id()));

            return job;
        } finally {
            mutex.unlock();
        }
    }

    /**
     * 取消渲染任务
     *
     * @param jobId 任务ID
     * @return 更新后的任务状态
     */
    public RenderJob cancel(long jobId) {
        RenderJob job = repository.findById(jobId);
        if (job.status() != RenderJobStatus.QUEUED && job.status() != RenderJobStatus.RUNNING) {
            throw new RenderJobConflictException("RENDER_JOB_NOT_CANCELLABLE", "任务已结束，不能取消");
        }

        if (job.status() == RenderJobStatus.RUNNING) {
            renderService.cancel(jobId);
        }
        repository.update(new RenderJob(job.id(), job.matchId(), job.templateCode(),
                RenderJobStatus.CANCELLED, job.progressPercent(), job.outputPath(), job.failureMessage(),
                job.createdAt(), job.startedAt(), Instant.now()));
        deleteWorkDir(jobId);
        return repository.findById(jobId);
    }

    /**
     * 获取任务详情
     */
    public RenderJob get(long jobId) {
        return repository.findById(jobId);
    }

    /**
     * 查询当前活跃任务
     */
    public Optional<RenderJob> findActive() {
        return repository.findActive();
    }

    /**
     * 应用启动时清理残留的渲染任务
     * 将状态为 QUEUED 或 RUNNING 的任务标记为 FAILED
     */
    public void failInterruptedJobs() {
        int killed = renderService.killOrphanedProcesses();
        if (killed > 0) {
            log.info("已清理 {} 个残留的 ffmpeg 进程", killed);
        }
        List<RenderJob> queued = repository.findByStatus(RenderJobStatus.QUEUED);
        List<RenderJob> running = repository.findByStatus(RenderJobStatus.RUNNING);
        String message = "应用在导出期间停止，任务未完成";
        for (RenderJob job : queued) {
            fail(job, message);
        }
        for (RenderJob job : running) {
            fail(job, message);
        }
    }

    /**
     * 执行渲染任务的核心逻辑
     */
    private void runJob(long jobId) {
        RenderJob job = repository.findById(jobId);
        try {
            markRunning(jobId);
            var project = projectRepository.findById(job.matchId());
            var video = videoAssetRepository.findById(project.videoAssetId());
            List<ScoreEvent> events = scoreEventRepository.findByMatchId(job.matchId());
            var replay = new com.scoresync.service.ScoreReplayService().replay(project.targetWins(), events);
            List<ScoreboardCue> cues = replay.scoreboardCues();

            VideoProbeResult probe = probeService.probe(Path.of(video.absolutePath()));
            Path workDir = Paths.get(appProperties.getRender().getWorkDir(), String.valueOf(jobId));
            RenderPlan plan = planService.create(project, video, cues, probe, workDir);
            FfmpegCommand command = commandFactory.create(plan, workDir);

            Path finalOutput = Paths.get(appProperties.getRender().getOutputDir(),
                    project.name() + "-" + job.templateCode().name() + "-" + job.id() + ".mp4");

            int[] lastLogged = {0};
            renderService.execute(jobId, plan, command, finalOutput, percent -> {
                updateProgress(jobId, percent);
                if (percent / 10 > lastLogged[0]) {
                    lastLogged[0] = percent / 10;
                    log.info("渲染进度: {}%", percent);
                }
            });

            repository.update(new RenderJob(jobId, job.matchId(), job.templateCode(),
                    RenderJobStatus.SUCCEEDED, 100, finalOutput.toString(), null,
                    job.createdAt(), Instant.now(), Instant.now()));
        } catch (Exception e) {
            log.warn("渲染任务 {} 失败: {}", jobId, e.getMessage());
            RenderJob current = repository.findById(jobId);
            String message = e.getMessage();
            if (message != null && message.length() > 200) {
                message = message.substring(0, 200);
            }
            repository.update(new RenderJob(jobId, current.matchId(), current.templateCode(),
                    RenderJobStatus.FAILED, current.progressPercent(), null, message,
                    current.createdAt(), current.startedAt(), Instant.now()));
            deleteWorkDir(jobId);
        }
    }

    private void markRunning(long jobId) {
        RenderJob job = repository.findById(jobId);
        repository.update(new RenderJob(jobId, job.matchId(), job.templateCode(),
                RenderJobStatus.RUNNING, 0, job.outputPath(), job.failureMessage(),
                job.createdAt(), Instant.now(), job.finishedAt()));
    }

    private void updateProgress(long jobId, int percent) {
        RenderJob job = repository.findById(jobId);
        repository.update(new RenderJob(jobId, job.matchId(), job.templateCode(),
                RenderJobStatus.RUNNING, percent, job.outputPath(), job.failureMessage(),
                job.createdAt(), job.startedAt(), job.finishedAt()));
    }

    private void fail(RenderJob job, String message) {
        repository.update(new RenderJob(job.id(), job.matchId(), job.templateCode(),
                RenderJobStatus.FAILED, job.progressPercent(), job.outputPath(), message,
                job.createdAt(), job.startedAt(), Instant.now()));
        deleteWorkDir(job.id());
    }

    /**
     * 删除渲染工作目录
     */
    private void deleteWorkDir(long jobId) {
        Path workDir = Paths.get(appProperties.getRender().getWorkDir(), String.valueOf(jobId));
        if (Files.exists(workDir)) {
            try (var walk = Files.walk(workDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void run(String... args) {
        failInterruptedJobs();
    }

    /**
     * 关闭服务时取消所有运行中的渲染任务
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
