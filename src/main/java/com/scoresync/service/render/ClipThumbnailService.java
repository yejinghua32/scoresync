package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.domain.ClipProject;
import com.scoresync.domain.VideoAsset;
import com.scoresync.service.ClipSourceValidator;
import com.scoresync.support.DomainValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 剪辑缩略图服务
 * 按帧索引定位源视频中的确切画面并生成限定尺寸的 PNG，
 * 结果按源文件指纹缓存，缓存总量受限以避免无限增长
 */
@Service
public class ClipThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ClipThumbnailService.class);

    private final ClipFrameIndexService indexService;
    private final ClipSourceValidator sourceValidator;
    private final ClipFrameExtractor extractor;
    private final AppProperties appProperties;

    /**
     * 限制同时进行的抽帧任务数，避免长视频上并发解码拖垮机器
     */
    private final Semaphore permits;

    public ClipThumbnailService(ClipFrameIndexService indexService,
                                ClipSourceValidator sourceValidator,
                                ClipFrameExtractor extractor,
                                AppProperties appProperties) {
        this.indexService = indexService;
        this.sourceValidator = sourceValidator;
        this.extractor = extractor;
        this.appProperties = appProperties;
        this.permits = new Semaphore(Math.max(1, appProperties.getClip().getMaxConcurrentThumbnailJobs()), true);
    }

    /**
     * 读取指定帧的缩略图
     *
     * @param project 剪辑项目
     * @param frameNo 帧序号，从 0 开始
     * @return PNG 字节
     */
    public byte[] thumbnail(ClipProject project, int frameNo) {
        ClipFrameIndex index = indexService.requireIndex(project);
        if (frameNo < 0 || frameNo >= index.frameCount()) {
            throw new DomainValidationException("帧序号超出范围，可用帧数为 0 到 " + (index.frameCount() - 1));
        }
        VideoAsset asset = sourceValidator.requireUnchangedSource(project);
        Path file = cacheFile(project, asset, frameNo);
        if (Files.isRegularFile(file)) {
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                // 缓存读取失败时重新生成
                log.debug("读取缩略图缓存失败: {}", e.getMessage());
            }
        }
        return generate(project, asset, index, frameNo, file);
    }

    /**
     * 生成缩略图并写入缓存
     */
    private byte[] generate(ClipProject project, VideoAsset asset, ClipFrameIndex index,
                            int frameNo, Path file) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainValidationException("生成缩略图被中断，请重试");
        }
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            extract(asset, index, frameNo, temp);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            pruneCache(file.getParent());
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("生成缩略图失败: " + e.getMessage(), e);
        } finally {
            permits.release();
        }
    }

    /**
     * 调用 ffmpeg 抽取指定帧
     * 用帧索引里的精确时间戳定位，配合精确跳转保证取到的是该帧画面
     */
    private void extract(VideoAsset asset, ClipFrameIndex index, int frameNo, Path output) {
        double seekSeconds = index.seekUs(frameNo) / 1_000_000d;
        extractor.extract(Path.of(asset.absolutePath()), seekSeconds, output);
    }

    /**
     * 缓存文件路径
     * 键包含资产 ID、文件大小与修改时间，源文件变化后旧缓存自然失效
     */
    private Path cacheFile(ClipProject project, VideoAsset asset, int frameNo) {
        String key = project.videoAssetId() + "-" + asset.fileSize() + "-" + asset.modifiedAt();
        return Path.of(appProperties.getClip().getCacheDir(), "thumbs", key, frameNo + ".png");
    }

    /**
     * 清理超出上限的缩略图缓存
     * 按修改时间删除最旧的文件
     */
    private void pruneCache(Path directory) {
        int limit = appProperties.getClip().getMaxCachedThumbnails();
        if (limit <= 0) {
            return;
        }
        try (var stream = Files.list(directory)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            if (files.size() <= limit) {
                return;
            }
            files.stream()
                    .sorted(Comparator.comparingLong(this::lastModified))
                    .limit(files.size() - limit)
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
