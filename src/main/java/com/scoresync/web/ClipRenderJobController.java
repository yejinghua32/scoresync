package com.scoresync.web;

import com.scoresync.domain.ClipRenderJob;
import com.scoresync.domain.ClipRenderJobStatus;
import com.scoresync.service.render.ClipRenderJobService;
import com.scoresync.support.DomainNotFoundException;
import com.scoresync.support.RenderJobConflictException;
import com.scoresync.web.dto.ClipRenderJobResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 剪辑导出任务控制器
 * 负责剪辑导出任务的创建、查询、取消和下载
 * 与记分渲染任务使用各自独立的路径，互不影响
 */
@RestController
@RequestMapping("/api")
public class ClipRenderJobController {

    private final ClipRenderJobService clipRenderJobService;

    public ClipRenderJobController(ClipRenderJobService clipRenderJobService) {
        this.clipRenderJobService = clipRenderJobService;
    }

    /**
     * 为剪辑项目创建导出任务
     */
    @PostMapping("/clip-projects/{id}/render-jobs")
    public ResponseEntity<ClipRenderJobResponse> createRenderJob(@PathVariable long id) {
        ClipRenderJob job = clipRenderJobService.start(id);
        return ResponseEntity.accepted().body(ClipRenderJobResponse.from(job));
    }

    /**
     * 获取剪辑导出任务详情
     */
    @GetMapping("/clip-render-jobs/{id}")
    public ResponseEntity<ClipRenderJobResponse> getRenderJob(@PathVariable long id) {
        return ResponseEntity.ok(ClipRenderJobResponse.from(clipRenderJobService.get(id)));
    }

    /**
     * 获取当前活跃的剪辑导出任务
     */
    @GetMapping("/clip-render-jobs/active")
    public ResponseEntity<ClipRenderJobResponse> getActiveRenderJob() {
        Optional<ClipRenderJob> active = clipRenderJobService.findActive();
        return active.map(job -> ResponseEntity.ok(ClipRenderJobResponse.from(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 取消正在执行的剪辑导出任务
     */
    @PostMapping("/clip-render-jobs/{id}/cancel")
    public ResponseEntity<ClipRenderJobResponse> cancelRenderJob(@PathVariable long id) {
        return ResponseEntity.ok(ClipRenderJobResponse.from(clipRenderJobService.cancel(id)));
    }

    /**
     * 下载剪辑导出的视频文件
     */
    @GetMapping("/clip-render-jobs/{id}/download")
    public ResponseEntity<?> downloadRenderJob(@PathVariable long id) {
        ClipRenderJob job = clipRenderJobService.get(id);
        if (job.status() != ClipRenderJobStatus.SUCCEEDED || job.outputPath() == null) {
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
