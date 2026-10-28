package com.scoresync.service;

import com.scoresync.domain.VideoAsset;
import com.scoresync.domain.VideoBulkDeleteResult;
import com.scoresync.domain.VideoOriginType;
import com.scoresync.domain.VideoSource;
import com.scoresync.repository.ClipProjectRepository;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.repository.VideoSourceRepository;
import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.DomainValidationException;
import com.scoresync.support.MediaUnavailableException;
import com.scoresync.support.VideoInUseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * 视频库服务
 * 负责管理视频数据源和视频资产的扫描、注册、查询等操作
 */
@Service
public class VideoLibraryService {

    private final VideoSourceRepository sourceRepo;
    private final VideoAssetRepository assetRepo;
    private final MatchProjectRepository projectRepo;
    private final ClipProjectRepository clipProjectRepo;

    public VideoLibraryService(VideoSourceRepository sourceRepo, VideoAssetRepository assetRepo,
                               MatchProjectRepository projectRepo, ClipProjectRepository clipProjectRepo) {
        this.sourceRepo = sourceRepo;
        this.assetRepo = assetRepo;
        this.projectRepo = projectRepo;
        this.clipProjectRepo = clipProjectRepo;
    }

    /**
     * 添加视频数据源
     *
     * @param rootPath 数据源根目录路径
     * @return 新创建的VideoSource对象
     */
    public VideoSource addSource(String rootPath) {
        Path path;
        try {
            path = Paths.get(rootPath).toRealPath();
        } catch (IOException e) {
            throw new DomainValidationException("视频目录不可读取");
        }
        if (!Files.isDirectory(path) || !Files.isReadable(path)) {
            throw new DomainValidationException("视频目录不可读取");
        }
        return sourceRepo.insert(path.toString());
    }

    /**
     * 扫描指定数据源，查找支持的视频文件并更新资产库
     *
     * @param sourceId 数据源ID
     * @return 新发现或更新的视频资产列表
     */
    public List<VideoAsset> scan(long sourceId) {
        VideoSource source = sourceRepo.findById(sourceId);
        if (source == null) {
            throw new DomainNotFoundException("VideoSource not found: " + sourceId);
        }
        Path root;
        try {
            root = Path.of(source.rootPath()).toRealPath();
        } catch (IOException e) {
            throw new DomainNotFoundException("VideoSource path not accessible: " + sourceId);
        }
        Set<String> supported = Set.of("mp4", "webm", "mov");
        Map<String, VideoAsset> pathToAsset = new HashMap<>();
        for (VideoAsset a : assetRepo.findAll()) {
            if (a.absolutePath() != null) pathToAsset.put(a.absolutePath(), a);
        }
        List<Path> files;
        try {
            files = Files.walk(root)
                    .filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                        for (String ext : supported) {
                            if (name.endsWith("." + ext)) return true;
                        }
                        return false;
                    })
                    .filter(Files::isReadable)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        List<VideoAsset> result = new ArrayList<>();
        for (Path file : files) {
            try {
                long size = Files.size(file);
                long modified = Files.getLastModifiedTime(file).toMillis();
                String displayName = file.getFileName().toString();
                VideoAsset existing = pathToAsset.get(file.toString());
                if (existing == null) {
                    long id = assetRepo.insert(sourceId, file.toString(), displayName, size, modified, VideoOriginType.SCANNED);
                    result.add(new VideoAsset(id, sourceId, file.toString(), displayName, size, modified, VideoOriginType.SCANNED, Instant.now(), Instant.now()));
                } else {
                    assetRepo.update(new VideoAsset(existing.id(), sourceId, file.toString(), displayName, size, modified, existing.originType(), existing.createdAt(), Instant.now()));
                    result.add(new VideoAsset(existing.id(), sourceId, file.toString(), displayName, size, modified, existing.originType(), existing.createdAt(), Instant.now()));
                }
            } catch (IOException ignored) {
            }
        }
        return result;
    }

    /**
     * 列出所有视频数据源
     */
    public List<VideoSource> listSources() {
        return sourceRepo.findAll();
    }

    /**
     * 列出所有视频资产
     */
    public List<VideoAsset> listAssets() {
        return assetRepo.findAll();
    }

    /**
     * 删除指定的数据源
     *
     * @param sourceId 数据源ID
     */
    public void removeSource(long sourceId) {
        VideoSource source = sourceRepo.findById(sourceId);
        if (source == null) {
            throw new DomainNotFoundException("VideoSource not found: " + sourceId);
        }
        sourceRepo.delete(sourceId);
    }

    /**
     * 从视频库移除视频资产
     * 只删除登记记录，磁盘上的文件保持不变；重新扫描该目录后仍会再次登记。
     * 仍被比赛项目引用的视频不允许移除，否则项目将失去视频来源。
     *
     * @param assetId 视频资产ID
     */
    @Transactional
    public void removeAsset(long assetId) {
        VideoAsset asset = assetRepo.findById(assetId);
        int usedBy = projectRepo.countByVideoAssetId(asset.id());
        int usedByClips = clipProjectRepo.countByVideoAssetId(asset.id());
        if (usedBy > 0 || usedByClips > 0) {
            int total = usedBy + usedByClips;
            throw new VideoInUseException("VIDEO_IN_USE", "该视频正被 " + total + " 个项目使用，无法移除");
        }
        assetRepo.delete(assetId);
    }

    /**
     * 清空视频库
     * 只删除登记记录，磁盘上的文件保持不变；重新扫描目录后仍会再次登记。
     * 仍被项目引用的视频会被跳过，并在结果中列出。
     *
     * @return 删除数量与被跳过的视频名称
     */
    public VideoBulkDeleteResult removeAllAssets() {
        List<String> skipped = new ArrayList<>();
        int deleted = 0;
        for (VideoAsset asset : assetRepo.findAll()) {
            try {
                removeAsset(asset.id());
                deleted++;
            } catch (VideoInUseException e) {
                skipped.add(asset.displayName());
            }
        }
        return new VideoBulkDeleteResult(deleted, skipped);
    }

    /**
     * 读取指定资产ID的视频资产信息，并验证文件仍然可访问
     *
     * @param assetId 资产ID
     * @return 视频资产对象
     */
    public VideoAsset readAsset(long assetId) {
        VideoAsset asset = assetRepo.findById(assetId);
        if (asset == null) {
            throw new DomainNotFoundException("VideoAsset not found: " + assetId);
        }
        try {
            Path.of(asset.absolutePath()).toRealPath();
        } catch (IOException e) {
            throw new MediaUnavailableException("Media file no longer available: " + assetId);
        }
        return asset;
    }

    /**
     * 注册导出的视频文件
     * 导出成功后把新文件登记回视频库，来源类型沿用 SCANNED
     *
     * @param path 导出文件路径
     * @return 新创建的视频资产对象
     */
    @Transactional
    public VideoAsset registerExported(Path path) {
        try {
            Path real = path.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
                throw new DomainValidationException("导出文件不可读取");
            }
            long size = Files.size(real);
            long modified = Files.getLastModifiedTime(real).toMillis();
            Long sourceId = findOrCreateExportsSource(real.getParent());
            long id = assetRepo.insert(sourceId, real.toString(), real.getFileName().toString(),
                    size, modified, VideoOriginType.SCANNED);
            return new VideoAsset(id, sourceId, real.toString(), real.getFileName().toString(),
                    size, modified, VideoOriginType.SCANNED, Instant.now(), Instant.now());
        } catch (IOException e) {
            throw new DomainValidationException("导出文件不可读取");
        }
    }

    /**
     * 找到或创建指向导出目录的视频源
     * 这样导出的文件在视频库里也能像普通扫描到的视频一样被使用
     */
    private Long findOrCreateExportsSource(Path exportsDir) {
        if (exportsDir == null) {
            return null;
        }
        String root = exportsDir.toString();
        for (VideoSource source : sourceRepo.findAll()) {
            if (root.equals(source.rootPath())) {
                return source.id();
            }
        }
        return sourceRepo.insert(root).id();
    }

    /**
     * 注册通过文件选择器选取的视频文件
     *
     * @param path 视频文件路径
     * @return 新创建的视频资产对象
     */
    public VideoAsset registerPicked(Path path) {
        try {
            Path real = path.toRealPath();
            if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
                throw new DomainValidationException("视频目录不可读取");
            }
            String name = real.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".mp4") && !name.endsWith(".webm") && !name.endsWith(".mov")) {
                throw new DomainValidationException("视频目录不可读取");
            }
            List<VideoSource> sources = sourceRepo.findAll();
            boolean withinSource = sources.stream().anyMatch(s -> {
                try {
                    Path srcRoot = Path.of(s.rootPath()).toRealPath();
                    return real.toString().startsWith(srcRoot.toString() + srcRoot.getFileSystem().getSeparator());
                } catch (IOException e) {
                    return false;
                }
            });
            if (!withinSource) {
                throw new DomainValidationException("视频目录不可读取");
            }
            long size = Files.size(real);
            long modified = Files.getLastModifiedTime(real).toMillis();
            long id = assetRepo.insert(null, real.toString(), real.getFileName().toString(), size, modified, VideoOriginType.PICKED);
            return new VideoAsset(id, null, real.toString(), real.getFileName().toString(), size, modified, VideoOriginType.PICKED, Instant.now(), Instant.now());
        } catch (IOException e) {
            throw new DomainValidationException("视频目录不可读取");
        }
    }
}
