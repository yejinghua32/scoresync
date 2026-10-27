package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.domain.ClipFrameIndexStatus;
import com.scoresync.domain.ClipProject;
import com.scoresync.domain.VideoAsset;
import com.scoresync.repository.ClipProjectRepository;
import com.scoresync.service.ClipSourceValidator;
import com.scoresync.support.DomainValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 剪辑帧索引服务
 * 负责按源文件指纹（资产 ID + 大小 + 修改时间）缓存逐帧索引，
 * 并在后台线程中建立索引，不阻塞应用启动与请求
 */
@Service
public class ClipFrameIndexService {

    private static final Logger log = LoggerFactory.getLogger(ClipFrameIndexService.class);
    private static final int CACHE_MAGIC = 0x434C4950;
    private static final int CACHE_VERSION = 1;

    private final ClipProjectRepository projectRepository;
    private final ClipSourceValidator sourceValidator;
    private final ClipFrameIndexBuilder indexBuilder;
    private final AppProperties appProperties;

    /**
     * 已加载到内存的索引，键为缓存键
     */
    private final Map<String, ClipFrameIndex> memoryCache = new ConcurrentHashMap<>();

    /**
     * 正在建立索引的项目 ID，避免重复触发
     */
    private final Map<Long, Boolean> building = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "clip-frame-index-worker");
        t.setDaemon(true);
        return t;
    });

    public ClipFrameIndexService(ClipProjectRepository projectRepository,
                                 ClipSourceValidator sourceValidator,
                                 ClipFrameIndexBuilder indexBuilder,
                                 AppProperties appProperties) {
        this.projectRepository = projectRepository;
        this.sourceValidator = sourceValidator;
        this.indexBuilder = indexBuilder;
        this.appProperties = appProperties;
    }

    /**
     * 读取项目的帧索引
     * 索引尚未就绪时返回 null
     */
    public ClipFrameIndex findIndex(ClipProject project) {
        if (project.frameIndexStatus() != ClipFrameIndexStatus.READY || project.totalFrames() == null) {
            return null;
        }
        VideoAsset asset = sourceValidator.requireUnchangedSource(project);
        String key = cacheKey(project, asset);
        ClipFrameIndex cached = memoryCache.get(key);
        if (cached != null) {
            return cached;
        }
        Path file = cacheFile(key);
        ClipFrameIndex loaded = Files.isRegularFile(file) ? readCache(file) : null;
        if (loaded == null) {
            // 缓存文件缺失或损坏：状态回退并重建，避免把过期状态当作可用索引
            projectRepository.updateFrameIndex(project.id(), ClipFrameIndexStatus.PENDING, null);
            requestBuild(projectRepository.findById(project.id()));
            return null;
        }
        memoryCache.put(key, loaded);
        return loaded;
    }

    /**
     * 读取项目的帧索引，索引不可用时抛出可恢复的中文错误
     */
    public ClipFrameIndex requireIndex(ClipProject project) {
        ClipFrameIndex index = findIndex(project);
        if (index == null) {
            throw new DomainValidationException("帧索引尚未就绪，请稍候重试");
        }
        return index;
    }

    /**
     * 请求建立帧索引
     * 已在建立或已就绪时直接返回，不会重复触发
     */
    public void requestBuild(ClipProject project) {
        if (project.frameIndexStatus() == ClipFrameIndexStatus.READY && project.totalFrames() != null) {
            return;
        }
        if (building.putIfAbsent(project.id(), Boolean.TRUE) != null) {
            return;
        }
        projectRepository.updateFrameIndex(project.id(), ClipFrameIndexStatus.BUILDING, null);
        executor.submit(() -> {
            try {
                buildAndPersist(project.id());
            } finally {
                building.remove(project.id());
            }
        });
    }

    /**
     * 建立索引并写入项目记录
     */
    private void buildAndPersist(long projectId) {
        ClipProject project = projectRepository.findById(projectId);
        try {
            VideoAsset asset = sourceValidator.requireUnchangedSource(project);
            String key = cacheKey(project, asset);
            Path file = cacheFile(key);
            ClipFrameIndex index = indexBuilder.build(Path.of(asset.absolutePath()));
            writeCache(file, index);
            memoryCache.put(key, index);
            projectRepository.updateFrameIndex(projectId, ClipFrameIndexStatus.READY, index.frameCount());
            log.info("剪辑项目 {} 的帧索引建立完成，共 {} 帧", projectId, index.frameCount());
        } catch (Exception e) {
            String message = e.getMessage() == null ? "建立帧索引失败" : e.getMessage();
            log.warn("剪辑项目 {} 的帧索引建立失败: {}", projectId, message);
            projectRepository.updateFrameIndex(projectId, ClipFrameIndexStatus.FAILED, null);
        }
    }

    /**
     * 缓存键：资产 ID + 文件大小 + 修改时间
     * 源文件变化后旧缓存自然失效
     */
    String cacheKey(ClipProject project, VideoAsset asset) {
        return project.videoAssetId() + "-" + asset.fileSize() + "-" + asset.modifiedAt();
    }

    private Path cacheFile(String key) {
        return Path.of(appProperties.getClip().getCacheDir(), "frames", key + ".idx");
    }

    /**
     * 写入索引缓存
     */
    private void writeCache(Path file, ClipFrameIndex index) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp);
                 DataOutputStream data = new DataOutputStream(out)) {
                data.writeInt(CACHE_MAGIC);
                data.writeInt(CACHE_VERSION);
                data.writeInt(index.frameCount());
                data.writeLong(index.containerStartUs());
                for (int i = 0; i < index.frameCount(); i++) {
                    data.writeLong(index.ptsUs(i));
                }
                for (int i = 0; i < index.frameCount(); i++) {
                    data.writeLong(index.durationUs(i));
                }
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写入帧索引缓存失败", e);
        }
    }

    /**
     * 读取索引缓存，缓存损坏时返回 null
     */
    private ClipFrameIndex readCache(Path file) {
        try (InputStream in = Files.newInputStream(file);
             DataInputStream data = new DataInputStream(in)) {
            if (data.readInt() != CACHE_MAGIC || data.readInt() != CACHE_VERSION) {
                return null;
            }
            int count = data.readInt();
            if (count <= 0) {
                return null;
            }
            long containerStartUs = data.readLong();
            long[] pts = new long[count];
            for (int i = 0; i < count; i++) {
                pts[i] = data.readLong();
            }
            long[] durations = new long[count];
            for (int i = 0; i < count; i++) {
                durations[i] = data.readLong();
            }
            return new ClipFrameIndex(pts, durations, containerStartUs);
        } catch (IOException e) {
            return null;
        }
    }
}
