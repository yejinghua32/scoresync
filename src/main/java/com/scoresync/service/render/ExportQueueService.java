package com.scoresync.service.render;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 记分导出与剪辑导出共用的全局 FIFO 队列。
 * 业务服务在提交锁内完成项目内重复检查、业务校验和 QUEUED 任务写入，再把任务交给这里执行。
 * 队列只有一个 worker，因此全进程最多只有一个导出任务进入 RUNNING。
 */
@Service
public class ExportQueueService {

    private static final Logger log = LoggerFactory.getLogger(ExportQueueService.class);

    private final ReentrantLock submitLock = new ReentrantLock();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "export-queue-worker");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 在全局提交锁内执行项目范围检查和任务写入，避免并发重复提交。
     */
    public <T> T submitExclusively(Supplier<T> action) {
        submitLock.lock();
        try {
            return action.get();
        } finally {
            submitLock.unlock();
        }
    }

    /**
     * FIFO 提交一个已经持久化为 QUEUED 的任务。任务自身负责将状态转为 RUNNING、
     * 执行导出并更新终态；此处的异常只终止单个任务，不会终止 worker。
     */
    public void enqueue(Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("导出队列任务异常", e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        // 终止排队任务；运行中的 FFmpeg 由 FfmpegRenderService 的 Bean 生命周期负责清理
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
