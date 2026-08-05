package com.scoresync.service;

import com.scoresync.domain.*;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.DomainValidationException;
import com.scoresync.web.dto.BackupDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 备份服务
 * 提供项目的JSON格式导出和导入功能，用于编辑器与视频库之间的数据迁移
 */
@Service
public class BackupService {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final MatchProjectRepository projectRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final ScoreEventRepository eventRepository;
    private final ScoreReplayService replayService;
    private final ProjectService projectService;

    public BackupService(MatchProjectRepository projectRepository,
                         VideoAssetRepository videoAssetRepository,
                         ScoreEventRepository eventRepository,
                         ScoreReplayService replayService,
                         ProjectService projectService) {
        this.projectRepository = projectRepository;
        this.videoAssetRepository = videoAssetRepository;
        this.eventRepository = eventRepository;
        this.replayService = replayService;
        this.projectService = projectService;
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
                project.firstServer().name());

        BackupDocument.BackupVideo backupVideo = new BackupDocument.BackupVideo(
                video.displayName(),
                video.fileSize(),
                video.modifiedAt(),
                video.originType().name());

        return new BackupDocument(SUPPORTED_SCHEMA_VERSION, backupProject, backupVideo, backupEvents);
    }

    /**
     * 从备份文档导入项目
     * 导入前需先找到与原视频元信息（文件名、大小、修改时间）匹配的视频资产
     */
    @Transactional
    public EditorState importDocument(BackupDocument document) {
        validateSchema(document);

        VideoAsset asset = findSingleAsset(
                document.video().displayName(),
                document.video().fileSize(),
                document.video().modifiedAt());

        MatchProject project = projectRepository.findById(
                projectRepository.insert(
                        document.project().name(),
                        asset.id(),
                        document.project().durationMs(),
                        document.project().playerA(),
                        document.project().playerB(),
                        document.project().targetWins(),
                        ScoreboardTemplate.CLASSIC,
                        document.project().firstServer() != null
                                ? PlayerSide.valueOf(document.project().firstServer())
                                : PlayerSide.A));

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

        return projectService.editorState(project.id());
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
     */
    private VideoAsset findSingleAsset(String displayName, long fileSize, long modifiedAt) {
        List<VideoAsset> candidates = videoAssetRepository.findAll().stream()
                .filter(v -> v.displayName().equals(displayName))
                .filter(v -> v.fileSize() == fileSize)
                .filter(v -> v.modifiedAt() == modifiedAt)
                .toList();

        if (candidates.isEmpty()) {
            throw new DomainValidationException("未找到与备份匹配的视频");
        }
        if (candidates.size() > 1) {
            throw new DomainValidationException("找到多个与备份匹配的视频");
        }
        return candidates.get(0);
    }
}
