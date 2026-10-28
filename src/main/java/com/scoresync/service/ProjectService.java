package com.scoresync.service;

import com.scoresync.domain.*;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.RenderJobRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.DomainValidationException;
import com.scoresync.support.RenderJobConflictException;
import com.scoresync.web.dto.PageRequest;
import com.scoresync.web.dto.PageResponse;
import com.scoresync.web.dto.ProjectSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目服务
 * 管理比赛项目的创建、查询、编辑状态维护等核心业务逻辑
 */
@Service
public class ProjectService {

    private final MatchProjectRepository projectRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final ScoreEventRepository scoreEventRepository;
    private final RenderJobRepository renderJobRepository;
    private final ScoreReplayService replayService;

    public ProjectService(MatchProjectRepository projectRepository,
                          VideoAssetRepository videoAssetRepository,
                          ScoreEventRepository scoreEventRepository,
                          RenderJobRepository renderJobRepository,
                          ScoreReplayService replayService) {
        this.projectRepository = projectRepository;
        this.videoAssetRepository = videoAssetRepository;
        this.scoreEventRepository = scoreEventRepository;
        this.renderJobRepository = renderJobRepository;
        this.replayService = replayService;
    }

    /**
     * 创建新比赛项目
     */
    @Transactional
    public EditorState create(String name, long videoAssetId, long durationMs,
                              String playerA, String playerB, int targetWins) {
        return create(name, videoAssetId, durationMs, playerA, playerB, targetWins, false);
    }

    /**
     * 创建新比赛项目
     *
     * @param showProjectName 比分牌队名列表头是否显示项目名称
     */
    @Transactional
    public EditorState create(String name, long videoAssetId, long durationMs,
                              String playerA, String playerB, int targetWins,
                              boolean showProjectName) {
        if (name == null || name.isBlank()) {
            throw new DomainValidationException("项目名称不能为空");
        }
        if (playerA == null || playerA.isBlank()) {
            throw new DomainValidationException("选手A名称不能为空");
        }
        if (playerB == null || playerB.isBlank()) {
            throw new DomainValidationException("选手B名称不能为空");
        }
        if (targetWins < 1) {
            throw new DomainValidationException("目标获胜局数必须至少为1");
        }
        if (durationMs <= 0) {
            throw new DomainValidationException("视频时长必须为正数");
        }

        VideoAsset video = videoAssetRepository.findById(videoAssetId);

        long projectId = projectRepository.insert(name, videoAssetId, durationMs, playerA, playerB, targetWins,
                ScoreboardTemplate.CLASSIC, PlayerSide.A, showProjectName);

        return editorState(projectId);
    }

    /**
     * 获取项目的完整编辑状态，包含比赛信息和比分重放结果
     */
    public EditorState editorState(long projectId) {
        MatchProject project = projectRepository.findById(projectId);
        VideoAsset video = videoAssetRepository.findById(project.videoAssetId());
        List<ScoreEvent> storedEvents = scoreEventRepository.findByMatchId(projectId);
        var match = replayService.replay(project.targetWins(), storedEvents);
        return new EditorState(project, video, match, match.events(), match.scoreboardCues());
    }

    /**
     * 分页列出所有项目摘要
     * 可按项目名称与选手名称过滤
     */
    public PageResponse<ProjectSummary> list(int page, int size, String search) {
        PageRequest request = new PageRequest(page, size);
        int total = projectRepository.count(search);
        int totalPages = request.totalPages(total);
        int currentPage = request.page() >= totalPages && totalPages > 0 ? totalPages - 1 : request.page();
        List<ProjectSummary> items = projectRepository.findAll(currentPage * request.size(), request.size(), search)
                .stream()
                .map(p -> {
                    String videoName;
                    try {
                        videoName = videoAssetRepository.findById(p.videoAssetId()).displayName();
                    } catch (DomainNotFoundException e) {
                        videoName = "(视频已删除)";
                    }
                    return new ProjectSummary(p.id(), p.name(), p.playerA(), p.playerB(),
                            p.targetWins(), p.videoAssetId(), videoName, p.showProjectName(),
                            p.createdAt(), p.updatedAt());
                })
                .toList();
        return new PageResponse<>(items, currentPage, request.size(), total, totalPages);
    }

    /**
     * 更新项目的比分牌模板
     */
    @Transactional
    public EditorState updateScoreboardTemplate(long projectId, ScoreboardTemplate template) {
        MatchProject project = projectRepository.findById(projectId);
        MatchProject updated = new MatchProject(project.id(), project.name(), project.videoAssetId(),
                project.durationMs(), project.playerA(), project.playerB(), project.targetWins(),
                template, project.firstServer(), project.showProjectName(),
                project.createdAt(), project.updatedAt());
        projectRepository.update(updated);
        return editorState(projectId);
    }

    /**
     * 更新项目的基本信息（名称、选手、目标局数）
     */
    @Transactional
    public EditorState updateProject(long projectId, String name, String playerA, String playerB, int targetWins) {
        return updateProject(projectId, name, playerA, playerB, targetWins, null);
    }

    /**
     * 更新项目的基本信息
     *
     * @param showProjectName 比分牌队名列表头是否显示项目名称；为 null 表示不改动
     */
    @Transactional
    public EditorState updateProject(long projectId, String name, String playerA, String playerB, int targetWins,
                                    Boolean showProjectName) {
        MatchProject project = projectRepository.findById(projectId);
        boolean showName = showProjectName == null ? project.showProjectName() : showProjectName;
        MatchProject updated = new MatchProject(project.id(), name, project.videoAssetId(),
                project.durationMs(), playerA, playerB, targetWins,
                project.scoreboardTemplate(), project.firstServer(), showName,
                project.createdAt(), project.updatedAt());
        projectRepository.update(updated);
        return editorState(projectId);
    }

    /**
     * 更新首次发球方
     */
    @Transactional
    public EditorState updateFirstServer(long projectId, PlayerSide firstServer) {
        MatchProject project = projectRepository.findById(projectId);
        MatchProject updated = new MatchProject(project.id(), project.name(), project.videoAssetId(),
                project.durationMs(), project.playerA(), project.playerB(), project.targetWins(),
                project.scoreboardTemplate(), firstServer, project.showProjectName(),
                project.createdAt(), project.updatedAt());
        projectRepository.update(updated);
        return editorState(projectId);
    }

    /**
     * 删除项目及其关联的得分事件和渲染任务
     * 正在渲染中的项目无法删除
     */
    @Transactional
    public void delete(long projectId) {
        projectRepository.findById(projectId);
        if (renderJobRepository.findActiveByMatchId(projectId).isPresent()) {
            throw new RenderJobConflictException("RENDER_JOB_ACTIVE",
                    "项目正在渲染中，无法删除");
        }
        scoreEventRepository.deleteByMatchId(projectId);
        renderJobRepository.deleteByMatchId(projectId);
        projectRepository.delete(projectId);
    }
}
