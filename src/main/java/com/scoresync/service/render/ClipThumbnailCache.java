package com.scoresync.service.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 剪辑缩略图磁盘缓存
 * <p>
 * 所有源指纹目录共享同一个字节上限，容量超限时按最近使用时间淘汰最旧的缩略图。
 * 使用时间在命中时也会刷新，因此重启后仍能保持淘汰顺序，且不需要每次写入都全量扫描磁盘。
 * <p>
 * 缓存按版本分目录存放，版本号包含编码参数，格式或画质变化时旧目录会被回收，
 * 不会被当成新参数的结果。
 */
class ClipThumbnailCache {

    private static final Logger log = LoggerFactory.getLogger(ClipThumbnailCache.class);

    /**
     * 单个缓存文件
     */
    private record Entry(long size, long lastAccessMillis) {
    }

    private final Path root;
    private final String version;
    private final long maxBytes;
    private final Map<Path, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicBoolean indexed = new AtomicBoolean(false);

    ClipThumbnailCache(Path cacheDir, long maxBytes, String version) {
        this.root = cacheDir.resolve("thumbs");
        this.version = version;
        this.maxBytes = Math.max(0, maxBytes);
    }

    /**
     * 缓存文件路径
     * 键包含源文件指纹，源文件变化后旧缓存自然失效
     */
    Path fileFor(String fingerprint, int frameNo, String extension) {
        return root.resolve(version).resolve(fingerprint).resolve(frameNo + "." + extension);
    }

    /**
     * 读取缓存内容
     * 命中时刷新使用时间，未命中或读取失败时返回 null
     */
    byte[] read(Path file) {
        ensureIndexed();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file);
            touch(file, data.length);
            return data;
        } catch (IOException e) {
            log.debug("读取缩略图缓存失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 在目标文件旁创建临时文件
     * 抽帧结果先写入临时文件，再原子替换到目标位置，避免并发读到写了一半的内容
     */
    Path createTempFile(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        return Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
    }

    /**
     * 提交生成好的缩略图
     */
    void publish(Path temp, Path target) throws IOException {
        ensureIndexed();
        long size = Files.size(temp);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        Entry previous = entries.put(target, new Entry(size, System.currentTimeMillis()));
        if (previous != null) {
            totalBytes.addAndGet(size - previous.size());
        } else {
            totalBytes.addAndGet(size);
        }
        prune();
    }

    /**
     * 容量超限时按最近使用时间淘汰
     * 正在写入的临时文件不在索引内，不会被淘汰；删除失败的条目保留到下次再试
     */
    void prune() {
        if (maxBytes <= 0) {
            return;
        }
        long total = totalBytes.get();
        if (total <= maxBytes) {
            return;
        }
        List<Path> candidates = new ArrayList<>(entries.keySet());
        candidates.sort(Comparator.comparingLong(path -> entries.get(path).lastAccessMillis()));
        long overflow = total - maxBytes;
        for (Path path : candidates) {
            if (overflow <= 0) {
                break;
            }
            Entry entry = entries.get(path);
            if (entry == null) {
                continue;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // 文件正被读取或被占用，留到下次再淘汰
                continue;
            }
            entries.remove(path);
            totalBytes.addAndGet(-entry.size());
            overflow -= entry.size();
        }
        removeEmptyDirectories();
    }

    /**
     * 回收非当前版本的缓存目录
     */
    void cleanLegacyVersions() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            for (Path child : stream.toList()) {
                if (child.getFileName().toString().equals(version)) {
                    continue;
                }
                deleteRecursively(child);
                log.info("回收旧版缩略图缓存: {}", child);
            }
        } catch (IOException e) {
            log.debug("回收旧版缩略图缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 首次使用时建立内存索引
     * 只扫描一次，之后命中与写入都在内存中维护
     */
    private void ensureIndexed() {
        if (indexed.get()) {
            return;
        }
        synchronized (indexed) {
            if (indexed.get()) {
                return;
            }
            cleanLegacyVersions();
            Path versionRoot = root.resolve(version);
            if (Files.isDirectory(versionRoot)) {
                try (var stream = Files.walk(versionRoot)) {
                    for (Path path : stream.toList()) {
                        if (!Files.isRegularFile(path) || path.getFileName().toString().endsWith(".tmp")) {
                            continue;
                        }
                        try {
                            long size = Files.size(path);
                            long modified = Files.getLastModifiedTime(path).toMillis();
                            entries.put(path, new Entry(size, modified));
                            totalBytes.addAndGet(size);
                        } catch (IOException ignored) {
                        }
                    }
                } catch (IOException ignored) {
                }
            }
            indexed.set(true);
        }
    }

    /**
     * 刷新使用时间
     * 文件修改时间同时写入磁盘，重启后仍能保持淘汰顺序
     */
    private void touch(Path file, long size) {
        long now = System.currentTimeMillis();
        entries.put(file, new Entry(size, now));
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(now));
        } catch (IOException ignored) {
        }
    }

    private void removeEmptyDirectories() {
        Path versionRoot = root.resolve(version);
        if (!Files.isDirectory(versionRoot)) {
            return;
        }
        try (var stream = Files.list(versionRoot)) {
            for (Path directory : stream.toList()) {
                try (var children = Files.list(directory)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(child);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.debug("删除旧缓存目录失败: {}", e.getMessage());
        }
    }
}
