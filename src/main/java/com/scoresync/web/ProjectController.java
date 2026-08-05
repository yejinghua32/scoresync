package com.scoresync.web;

/**
 * 比赛项目控制器
 * 负责比赛项目的创建、查询、删除及属性更新
 */

import com.scoresync.domain.EditorState;
import com.scoresync.service.ProjectService;
import com.scoresync.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 创建新的比赛项目
     */
    @PostMapping("/projects")
    public ResponseEntity<EditorState> createProject(@Valid @RequestBody CreateProjectRequest request) {
        EditorState state = projectService.create(
                request.name(),
                request.videoId(),
                request.durationMs(),
                request.playerA(),
                request.playerB(),
                request.targetWins());
        return ResponseEntity.status(HttpStatus.CREATED).body(state);
    }

    /**
     * 分页获取项目列表
     */
    @GetMapping("/projects")
    public PageResponse<ProjectSummary> listProjects(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return projectService.list(page, size);
    }

    /**
     * 删除项目（同时删除关联的得分事件）
     */
    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> deleteProject(@PathVariable long id) {
        projectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取项目完整编辑器状态（包含当前比分、 cue 点等）
     */
    @GetMapping("/projects/{id}/editor-state")
    public EditorState getEditorState(@PathVariable long id) {
        return projectService.editorState(id);
    }

    /**
     * 更新项目的记分板模板
     */
    @PatchMapping("/projects/{id}/scoreboard-template")
    public ResponseEntity<EditorState> updateScoreboardTemplate(@PathVariable long id,
                                                                @Valid @RequestBody UpdateScoreboardTemplateRequest request) {
        EditorState state = projectService.updateScoreboardTemplate(id, request.templateCode());
        return ResponseEntity.ok(state);
    }

    /**
     * 更新首发发球方
     */
    @PatchMapping("/projects/{id}/first-server")
    public ResponseEntity<EditorState> updateFirstServer(@PathVariable long id,
                                                         @Valid @RequestBody UpdateFirstServerRequest request) {
        EditorState state = projectService.updateFirstServer(id, request.firstServer());
        return ResponseEntity.ok(state);
    }

    /**
     * 更新项目基本信息（名称、选手、目标局数）
     */
    @PutMapping("/projects/{id}")
    public ResponseEntity<EditorState> updateProject(@PathVariable long id,
                                                     @Valid @RequestBody UpdateProjectRequest request) {
        EditorState state = projectService.updateProject(id, request.name(), request.playerA(), request.playerB(), request.targetWins());
        return ResponseEntity.ok(state);
    }
}
