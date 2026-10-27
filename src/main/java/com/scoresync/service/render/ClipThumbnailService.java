package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.domain.ClipProject;
import com.scoresync.domain.ClipThumbnailFormat;
import com.scoresync.domain.ClipThumbnailSpec;
import com.scoresync.domain.VideoAsset;
import com.scoresync.service.ClipSourceValidator;
import com.scoresync.support.DomainValidationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * 剪辑缩略图服务
 * 按帧索引定位源视频中的确切画面并生成限定尺寸的缩略图，
 * 结果按源文件指纹缓存，磁盘总量受限以避免无限增长
 * <p>
 * 提供两档尺寸：当前帧用 large 档，画面更大更清晰；前后邻帧用 small 档，传输量更小。
 * <p>
 * 同一帧的并发请求合并为一次抽帧，避免重复启动 FFmpeg；
 * 前台请求（当前帧）优先于后台预取，预取不会占满全部抽帧名额
 */
@Service
public class ClipThumbnailService {

    /**
     * 请求大尺寸缩略图的档位名
     */
    public static final String LARGE_SIZE = "large";

    private final ClipFrameIndexService indexService;
    private final ClipSourceValidator sourceValidator;
    private final ClipFrameExtractor extractor;
    private final AppProperties appProperties;
    private final ClipThumbnailCache cache;
    private final ClipThumbnailFormat format;
    private final ClipThumbnailSpec smallSpec;
    private final ClipThumbnailSpec largeSpec;

    /**
     * 正在生成中的缩略图，键为缓存文件路径
     */
    private final Map<String, CompletableFuture<byte[]>> generating = new ConcurrentHashMap<>();

    /**
     * 抽帧名额的闸门
     */
    private final Object gate = new Object();
    private int active;
    private int waitingForeground;

    /**
     * 同时进行的抽帧任务数上限
     */
    private final int maxConcurrent;

    /**
     * 后台预取可占用的名额上限
     * 始终比总数少一，保证前台请求随时有名额可用
     */
    private final int backgroundLimit;

    public ClipThumbnailService(ClipFrameIndexService indexService,
                                ClipSourceValidator sourceValidator,
                                ClipFrameExtractor extractor,
                                AppProperties appProperties) {
        this.indexService = indexService;
        this.sourceValidator = sourceValidator;
        this.extractor = extractor;
        this.appProperties = appProperties;
        this.format = ClipThumbnailFormat.parse(appProperties.getClip().getThumbnailFormat());
        this.smallSpec = new ClipThumbnailSpec("small",
                appProperties.getClip().getThumbnailWidth(),
                appProperties.getClip().getThumbnailQuality(), format);
        this.largeSpec = new ClipThumbnailSpec(LARGE_SIZE,
                appProperties.getClip().getThumbnailLargeWidth(),
                appProperties.getClip().getThumbnailLargeQuality(), format);
        this.cache = new ClipThumbnailCache(Path.of(appProperties.getClip().getCacheDir()),
                appProperties.getClip().getMaxCachedThumbnailBytes(), cacheVersion(),
                Set.of(smallSpec.cacheKey(), largeSpec.cacheKey()));
        this.maxConcurrent = Math.max(1, appProperties.getClip().getMaxConcurrentThumbnailJobs());
        this.backgroundLimit = this.maxConcurrent >= 2 ? this.maxConcurrent - 1 : 0;
    }

    /**
     * 缩略图格式
     */
    public ClipThumbnailFormat thumbnailFormat() {
        return format;
    }

    /**
     * 按请求的尺寸选择档位
     * 未指定或无法识别时使用小尺寸
     */
    public ClipThumbnailSpec specFor(String size) {
        if (size != null && LARGE_SIZE.equalsIgnoreCase(size.trim())) {
            return largeSpec;
        }
        return smallSpec;
    }

    /**
     * 缓存版本目录名
     * 带上格式，格式变化时旧缓存自然失效
     */
    private String cacheVersion() {
        return "v3-" + format.extension();
    }

    /**
     * 读取指定帧的缩略图
     *
     * @param project 剪辑项目
     * @param frameNo 帧序号，从 0 开始
     * @return 缩略图字节
     */
    public byte[] thumbnail(ClipProject project, int frameNo) {
        return thumbnail(project, frameNo, smallSpec, true);
    }

    /**
     * 读取指定帧的缩略图
     *
     * @param project    剪辑项目
     * @param frameNo    帧序号，从 0 开始
     * @param spec       尺寸与画质档位
     * @param foreground 是否为前台请求，前台请求优先获得抽帧名额
     * @return 缩略图字节
     */
    public byte[] thumbnail(ClipProject project, int frameNo, ClipThumbnailSpec spec, boolean foreground) {
        ClipFrameIndex index = indexService.requireIndex(project);
        if (frameNo < 0 || frameNo >= index.frameCount()) {
            throw new DomainValidationException("帧序号超出范围，可用帧数为 0 到 " + (index.frameCount() - 1));
        }
        VideoAsset asset = sourceValidator.requireUnchangedSource(project);
        Path file = cache.fileFor(spec.cacheKey(), fingerprint(project, asset), frameNo, spec.format().extension());
        byte[] cached = cache.read(file);
        if (cached != null) {
            return cached;
        }
        return generateCoalesced(project, asset, index, frameNo, file, spec, foreground);
    }

    /**
     * 生成缩略图
     * 同一缓存键的并发请求只抽一次帧，其余请求复用同一个结果
     */
    private byte[] generateCoalesced(ClipProject project, VideoAsset asset, ClipFrameIndex index,
                                     int frameNo, Path file, ClipThumbnailSpec spec, boolean foreground) {
        String key = file.toString();
        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        CompletableFuture<byte[]> leader = generating.putIfAbsent(key, mine);
        if (leader != null) {
            return await(leader);
        }
        try {
            // 上一位生成者可能刚好写完缓存，重新读一次避免重复抽帧
            byte[] cached = cache.read(file);
            if (cached != null) {
                mine.complete(cached);
                return cached;
            }
            byte[] data = generate(project, asset, index, frameNo, file, spec, foreground);
            mine.complete(data);
            return data;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            generating.remove(key, mine);
        }
    }

    /**
     * 生成缩略图并写入缓存
     */
    private byte[] generate(ClipProject project, VideoAsset asset, ClipFrameIndex index,
                            int frameNo, Path file, ClipThumbnailSpec spec, boolean foreground) {
        acquire(foreground);
        try {
            Path temp = null;
            try {
                temp = cache.createTempFile(file);
                extract(asset, index, frameNo, temp, spec);
                byte[] data = Files.readAllBytes(temp);
                cache.publish(temp, file);
                return data;
            } catch (IOException e) {
                throw new IllegalStateException("生成缩略图失败: " + e.getMessage(), e);
            } finally {
                // 生成失败时清掉半成品，成功时文件已移走
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                    }
                }
            }
        } finally {
            release();
        }
    }

    /**
     * 等待同一帧的其他请求生成完毕
     */
    private byte[] await(CompletableFuture<byte[]> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainValidationException("生成缩略图被中断，请重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("生成缩略图失败", cause);
        }
    }

    /**
     * 调用 ffmpeg 抽取指定帧
     * 用帧索引里的精确时间戳定位，配合精确跳转保证取到的是该帧画面
     */
    private void extract(VideoAsset asset, ClipFrameIndex index, int frameNo, Path output, ClipThumbnailSpec spec) {
        double seekSeconds = index.seekUs(frameNo) / 1_000_000d;
        extractor.extract(Path.of(asset.absolutePath()), seekSeconds, output, spec);
    }

    /**
     * 缓存键
     * 键包含资产 ID、文件大小与修改时间，源文件变化后旧缓存自然失效
     */
    private String fingerprint(ClipProject project, VideoAsset asset) {
        return project.videoAssetId() + "-" + asset.fileSize() + "-" + asset.modifiedAt();
    }

    /**
     * 占用抽帧名额
     * 前台请求只受总名额限制，后台预取不能占满名额，且有前台请求排队时不再启动
     */
    private void acquire(boolean foreground) {
        synchronized (gate) {
            if (foreground) {
                waitingForeground++;
            }
            try {
                while (!canRun(foreground)) {
                    gate.wait();
                }
                active++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DomainValidationException("生成缩略图被中断，请重试");
            } finally {
                if (foreground) {
                    waitingForeground--;
                }
            }
        }
    }

    private boolean canRun(boolean foreground) {
        if (foreground) {
            return active < maxConcurrent;
        }
        return active < backgroundLimit && waitingForeground == 0;
    }

    private void release() {
        synchronized (gate) {
            active--;
            gate.notifyAll();
        }
    }
}
