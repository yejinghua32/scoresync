package com.scoresync.service.render;

import com.scoresync.repository.ClipRenderJobRepository;
import com.scoresync.repository.RenderJobRepository;
import com.scoresync.support.RenderJobConflictException;
import org.springframework.stereotype.Service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 导出占用服务
 * 记分导出与剪辑导出共用同一把锁和同一套占用检查，
 * 保证全进程同一时刻只允许一个导出任务在执行
 */
@Service
public class ExportOccupancyService {

    private final RenderJobRepository renderJobRepository;
    private final ClipRenderJobRepository clipRenderJobRepository;

    /**
     * 导出互斥锁
     * 记分与剪辑导出共用，检查与写入在同一把锁内完成，避免并发下同时通过检查
     */
    private final ReentrantLock mutex = new ReentrantLock();

    public ExportOccupancyService(RenderJobRepository renderJobRepository,
                                  ClipRenderJobRepository clipRenderJobRepository) {
        this.renderJobRepository = renderJobRepository;
        this.clipRenderJobRepository = clipRenderJobRepository;
    }

    /**
     * 在占用检查通过的前提下执行动作
     * 检查与动作在同一把锁内完成，保证“检查后立即写入”不会被并发绕过
     *
     * @param code    冲突时返回的错误码
     * @param message 冲突时返回的中文提示
     * @param action  创建导出任务的动作
     * @throws RenderJobConflictException 存在活跃导出任务时抛出
     */
    public <T> T runExclusively(String code, String message, Supplier<T> action) {
        mutex.lock();
        try {
            assertAvailable(code, message);
            return action.get();
        } finally {
            mutex.unlock();
        }
    }

    /**
     * 确认当前没有活跃的导出任务
     */
    public void assertAvailable(String code, String message) {
        if (hasActiveExport()) {
            throw new RenderJobConflictException(code, message);
        }
    }

    /**
     * 是否存在活跃的导出任务（记分或剪辑）
     */
    public boolean hasActiveExport() {
        return renderJobRepository.findActive().isPresent() || clipRenderJobRepository.findActive().isPresent();
    }
}
