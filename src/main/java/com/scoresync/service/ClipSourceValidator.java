package com.scoresync.service;

import com.scoresync.domain.ClipProject;
import com.scoresync.domain.VideoAsset;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.ClipSourceChangedException;
import com.scoresync.support.MediaUnavailableException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 剪辑源文件校验器
 * 保证剪辑项目始终绑定建立帧索引时的那个源文件：
 * 文件缺失时给出媒体不可用错误，大小或修改时间变化时给出源文件已变化错误
 */
@Service
public class ClipSourceValidator {

    private final VideoAssetRepository assetRepository;

    public ClipSourceValidator(VideoAssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    /**
     * 读取剪辑项目的源视频资产，并确认文件仍然存在
     */
    public VideoAsset requireAsset(ClipProject project) {
        VideoAsset asset = assetRepository.findById(project.videoAssetId());
        if (!Files.isReadable(Path.of(asset.absolutePath()))) {
            throw new MediaUnavailableException("源视频文件不存在或不可读，无法继续剪辑");
        }
        return asset;
    }

    /**
     * 校验源文件与建立帧索引时一致
     *
     * @throws ClipSourceChangedException 大小或修改时间与建立索引时不符
     */
    public VideoAsset requireUnchangedSource(ClipProject project) {
        VideoAsset asset = requireAsset(project);
        long size;
        long modifiedAt;
        try {
            Path path = Path.of(asset.absolutePath());
            size = Files.size(path);
            modifiedAt = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new MediaUnavailableException("源视频文件不存在或不可读，无法继续剪辑");
        }
        if (size != project.sourceFileSize() || modifiedAt != project.sourceModifiedAt()) {
            throw new ClipSourceChangedException("CLIP_SOURCE_CHANGED",
                    "源视频已发生变化，无法继续使用旧的帧序号，请恢复原文件或新建剪辑项目");
        }
        return asset;
    }

    /**
     * 读取视频资产，不存在时抛出 404
     */
    public VideoAsset requireAsset(long videoAssetId) {
        return assetRepository.findById(videoAssetId);
    }
}
