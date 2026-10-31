package com.scoresync.service;

import com.scoresync.domain.*;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.DomainValidationException;
import com.scoresync.web.dto.BackupDocument;
import com.scoresync.web.dto.ImportResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 备份服务
 * 提供项目的JSON格式导出和导入功能，用于编辑器与视频库之间的数据迁移
 */
@Service
public class BackupService {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    /**
     * 未关联视频的哨兵值：导入备份时原视频不存在，等待用户从视频库中替换
     */
    public static final long NO_VIDEO_ASSET_ID = 0L;

    private final MatchProjectRepository projectRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final ScoreEventRepository eventRepository;
    private final ScoreReplayService replayService;

    public BackupService(MatchProjectRepository projectRepository,
                         VideoAssetRepository videoAssetRepository,
                         ScoreEventRepository eventRepository,
                         ScoreReplayService replayService) {
        this.projectRepository = projectRepository;
        this.videoAssetRepository = videoAssetRepository;
        this.eventRepository = eventRepository;
        this.replayService = replayService;
    }

    /**
     * 导出项目为备份文档
     *
     * @param projectId 项目ID
     * @return 包含项目信息、视频信息、得分事件序列的备份文档
     */
    public BackupDocument exportProject(long projectId) {
        MatchProject project = projectRepository.findById(projectId);
        VideoAsset video = videoAssetRepository.findById(project.videoAssetId());
        List<ScoreEvent> events = eventRepository.findByMatchId(projectId);

        List<BackupDocument.BackupEvent> backupEvents = events.stream()
                .sorted(java.util.Comparator.comparingLong(ScoreEvent::videoTimeMs)
                        .thenComparingLong(ScoreEvent::sequenceNo))
                .map(e -> new BackupDocument.BackupEvent(
                        e.videoTimeMs(),
                        e.playerSide().name(),
                        e.sequenceNo()))
                .toList();

        BackupDocument.BackupProject backupProject = new BackupDocument.BackupProject(
                project.name(),
                project.durationMs(),
                project.playerA(),
                project.playerB(),
                project.targetWins(),
                project.firstServer().name(),
                project.showProjectName());

        BackupDocument.BackupVideo backupVideo = new BackupDocument.BackupVideo(
                video.displayName(),
                video.fileSize(),
                video.modifiedAt(),
                video.originType().name());

        return new BackupDocument(SUPPORTED_SCHEMA_VERSION, backupProject, backupVideo, backupEvents);
    }

    /**
     * 从备份文档导入项目
     * 若视频库中存在与原视频元信息（文件名、大小、修改时间）匹配的视频资产则直接关联；
     * 否则仍导入项目，视频留空（video_asset_id = 0），由用户在列表中选择替换视频。
     */
    @Transactional
    public ImportResult importDocument(BackupDocument document) {
        validateSchema(document);

        Optional<VideoAsset> matched = findMatchingAsset(
                document.video().displayName(),
                document.video().fileSize(),
                document.video().modifiedAt());

        MatchProject project = projectRepository.findById(
                projectRepository.insert(
                        document.project().name(),
                        matched.map(VideoAsset::id).orElse(NO_VIDEO_ASSET_ID),
                        document.project().durationMs(),
                        document.project().playerA(),
                        document.project().playerB(),
                        document.project().targetWins(),
                        ScoreboardTemplate.CLASSIC,
                        document.project().firstServer() != null
                                ? PlayerSide.valueOf(document.project().firstServer())
                                : PlayerSide.A,
                        document.project().showProjectNameOrDefault()));

        List<ScoreEvent> candidate = document.events().stream()
                .map(event -> new ScoreEvent(
                        0,
                        project.id(),
                        event.videoTimeMs(),
                        PlayerSide.valueOf(event.playerSide()),
                        event.sequenceNo(),
                        java.time.Instant.now(),
                        java.time.Instant.now()))
                .toList();

        replayService.replay(project.targetWins(), candidate);

        for (ScoreEvent event : candidate) {
            eventRepository.insert(project.id(), event.videoTimeMs(), event.playerSide());
        }

        return new ImportResult(project.id(), matched.isEmpty());
    }

    /**
     * 验证备份文档格式版本
     */
    private void validateSchema(BackupDocument document) {
        if (document.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new DomainValidationException("不支持的备份格式版本: " + document.schemaVersion());
        }
    }

    /**
     * 根据视频元信息查找匹配的视频资产
     * 未匹配到时返回空，由调用方决定如何处理；多个匹配时取 ID 最小的一个
     */
    private Optional<VideoAsset> findMatchingAsset(String displayName, long fileSize, long modifiedAt) {
        return videoAssetRepository.findAll().stream()
                .filter(v -> v.displayName().equals(displayName))
                .filter(v -> v.fileSize() == fileSize)
                .filter(v -> v.modifiedAt() == modifiedAt)
                .min(Comparator.comparingLong(VideoAsset::id));
    }
}
