package com.scoresync.web;

/**
 * 渲染任务控制器
 * 负责视频渲染任务的创建、查询、取消和下载
 */

import com.scoresync.domain.*;
import com.scoresync.service.ProjectService;
import com.scoresync.service.render.RenderJobService;
import com.scoresync.service.render.ScoreboardPreviewService;
import com.scoresync.service.render.ScoreboardSnapshotService;
import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.RenderJobConflictException;
import com.scoresync.web.dto.RenderJobResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class RenderJobController {

    private final ScoreboardPreviewService previewService;
    private final ScoreboardSnapshotService snapshotService;
    private final ProjectService projectService;
    private final RenderJobService renderJobService;

    public RenderJobController(ScoreboardPreviewService previewService,
                               ScoreboardSnapshotService snapshotService,
                               ProjectService projectService,
                               RenderJobService renderJobService) {
        this.previewService = previewService;
        this.snapshotService = snapshotService;
        this.projectService = projectService;
        this.renderJobService = renderJobService;
    }

    /**
     * 生成指定时刻的记分板预览图（PNG格式）
     */
    @GetMapping("/projects/{id}/scoreboard-preview")
    public ResponseEntity<byte[]> getScoreboardPreview(@PathVariable long id,
                                                       @RequestParam String template,
                                                       @RequestParam long timeMs,
                                                       @RequestParam int renderWidth) {
        EditorState state = projectService.editorState(id);
        MatchProject project = state.project();

        ScoreboardTemplate effectiveTemplate = template != null
                ? ScoreboardTemplate.valueOf(template)
                : project.scoreboardTemplate();

        List<ScoreboardCue> cues = state.scoreboardCues();
        if (cues == null || cues.isEmpty()) {
            cues = List.of(new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false));
        }

        ScoreboardSnapshot snapshot = snapshotService.at(project, cues, timeMs, renderWidth, renderWidth * 9 / 16);

        ScoreboardSnapshot previewSnapshot = new ScoreboardSnapshot(
                effectiveTemplate,
                snapshot.playerA(),
                snapshot.playerB(),
                snapshot.scoreA(),
                snapshot.scoreB(),
                snapshot.setNumber(),
                snapshot.setWinsA(),
                snapshot.setWinsB(),
                snapshot.matchCompleted(),
                snapshot.firstServer(),
                snapshot.frameWidth(),
                snapshot.frameHeight()
        );

        byte[] pngData = previewService.generatePreview(previewSnapshot);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setCacheControl("no-store");

        return ResponseEntity.ok().headers(headers).body(pngData);
    }

    /**
     * 为项目创建新的渲染任务（同一时刻仅允许一个活跃任务）
     */
    @PostMapping("/projects/{id}/render-jobs")
    public ResponseEntity<RenderJobResponse> createRenderJob(@PathVariable long id) {
        RenderJob job = renderJobService.start(id);
        return ResponseEntity.accepted().body(RenderJobResponse.from(job));
    }

    /**
     * 获取渲染任务详情
     */
    @GetMapping("/render-jobs/{id}")
    public ResponseEntity<RenderJobResponse> getRenderJob(@PathVariable long id) {
        return ResponseEntity.ok(RenderJobResponse.from(renderJobService.get(id)));
    }

    /**
     * 获取当前活跃的渲染任务（若有）
     */
    @GetMapping("/render-jobs/active")
    public ResponseEntity<RenderJobResponse> getActiveRenderJob() {
        Optional<RenderJob> active = renderJobService.findActive();
        return active.map(job -> ResponseEntity.ok(RenderJobResponse.from(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 取消正在执行的渲染任务
     */
    @PostMapping("/render-jobs/{id}/cancel")
    public ResponseEntity<RenderJobResponse> cancelRenderJob(@PathVariable long id) {
        return ResponseEntity.ok(RenderJobResponse.from(renderJobService.cancel(id)));
    }

    /**
     * 下载渲染完成的视频文件
     */
    @GetMapping("/render-jobs/{id}/download")
    public ResponseEntity<?> downloadRenderJob(@PathVariable long id) {
        RenderJob job = renderJobService.get(id);
        if (job.status() != RenderJobStatus.SUCCEEDED || job.outputPath() == null) {
            throw new RenderJobConflictException("RENDER_JOB_NOT_DOWNLOADABLE", "任务尚未成功完成，无法下载");
        }

        Path output = Path.of(job.outputPath());
        if (!output.toFile().exists()) {
            throw new DomainNotFoundException("导出文件不存在");
        }

        String filename = URLEncoder.encode(output.getFileName().toString(), StandardCharsets.UTF_8);
        FileSystemResource resource = new FileSystemResource(output);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("video/mp4"));
        headers.setContentDispositionFormData("attachment", filename);

        return ResponseEntity.ok().headers(headers).body(resource);
    }
}
