package com.scoresync.web;

import com.scoresync.domain.ClipSegmentSpec;
import com.scoresync.domain.ClipSegmentType;
import com.scoresync.domain.ClipThumbnailSpec;
import com.scoresync.service.ClipProjectService;
import com.scoresync.service.render.ClipFrameIndexService;
import com.scoresync.service.render.ClipThumbnailService;
import com.scoresync.support.DomainValidationException;
import com.scoresync.web.dto.ClipAllFrameTimesResponse;
import com.scoresync.web.dto.ClipFrameIndexStatusResponse;
import com.scoresync.web.dto.ClipProjectResponse;
import com.scoresync.web.dto.ClipSegmentRequest;
import com.scoresync.web.dto.CreateClipProjectRequest;
import com.scoresync.web.dto.PageResponse;
import com.scoresync.web.dto.SubmitClipSegmentsRequest;
import com.scoresync.web.dto.UpdateClipProjectRequest;
import com.scoresync.web.dto.UpdateClipSegmentBoundsRequest;
import com.scoresync.web.dto.UpdateClipSegmentFeaturedRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 剪辑项目控制器
 * 负责剪辑项目的创建、查询、配置更新、片段存档与帧缩略图
 */
@RestController
@RequestMapping("/api/clip-projects")
public class ClipProjectController {

    /**
     * 后台预取请求的优先级标记
     */
    private static final String BACKGROUND_PRIORITY = "background";

    private final ClipProjectService clipProjectService;
    private final ClipFrameIndexService frameIndexService;
    private final ClipThumbnailService thumbnailService;

    public ClipProjectController(ClipProjectService clipProjectService,
                                 ClipFrameIndexService frameIndexService,
                                 ClipThumbnailService thumbnailService) {
        this.clipProjectService = clipProjectService;
        this.frameIndexService = frameIndexService;
        this.thumbnailService = thumbnailService;
    }

    /**
     * 创建剪辑项目
     */
    @PostMapping
    public ResponseEntity<ClipProjectResponse> create(@Valid @RequestBody CreateClipProjectRequest request) {
        var detail = clipProjectService.create(request.name(), request.videoId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ClipProjectResponse.from(detail));
    }

    /**
     * 列出剪辑项目
     * 支持分页与按项目名、视频名搜索
     */
    @GetMapping
    public PageResponse<ClipProjectResponse> list(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "10") int size,
                                                   @RequestParam(required = false) String search) {
        return clipProjectService.listPage(page, size, search).map(ClipProjectResponse::from);
    }

    /**
     * 读取剪辑项目详情（含已存档片段）
     */
    @GetMapping("/{id}")
    public ClipProjectResponse get(@PathVariable long id) {
        return ClipProjectResponse.from(clipProjectService.detail(id));
    }

    /**
     * 更新剪辑项目名称与两个步长
     */
    @PutMapping("/{id}")
    public ClipProjectResponse update(@PathVariable long id,
                                       @Valid @RequestBody UpdateClipProjectRequest request) {
        return ClipProjectResponse.from(clipProjectService.updateConfig(
                id, request.name(), request.largeStep(), request.smallStep()));
    }

    /**
     * 提交片段（存档）
     * 新片段覆盖重叠的旧片段，相邻同类片段自动合并
     */
    @PostMapping("/{id}/segments")
    public ClipProjectResponse submitSegments(@PathVariable long id,
                                               @Valid @RequestBody SubmitClipSegmentsRequest request) {
        clipProjectService.commitSegments(id, toSpecs(request.segments()));
        return ClipProjectResponse.from(clipProjectService.detail(id));
    }

    /**
     * 删除一个已存档片段
     * 该区间的帧回到未标记状态
     */
    @DeleteMapping("/{id}/segments/{segmentId}")
    public ClipProjectResponse deleteSegment(@PathVariable long id, @PathVariable long segmentId) {
        clipProjectService.deleteSegment(id, segmentId);
        return ClipProjectResponse.from(clipProjectService.detail(id));
    }

    /**
     * 设置保留片段的精选状态
     * 舍弃片段不能设为精选
     */
    @PatchMapping("/{id}/segments/{segmentId}")
    public ClipProjectResponse setSegmentFeatured(@PathVariable long id, @PathVariable long segmentId,
                                                  @RequestBody UpdateClipSegmentFeaturedRequest request) {
        if (request == null || request.featured() == null) {
            throw new DomainValidationException("精选状态只能是 true 或 false");
        }
        return ClipProjectResponse.from(
                clipProjectService.setSegmentFeatured(id, segmentId, request.featured()));
    }

    /**
     * 调整已存档片段的起止帧
     * 片段类型与精选状态保持不变
     */
    @PutMapping("/{id}/segments/{segmentId}")
    public ClipProjectResponse updateSegmentBounds(@PathVariable long id, @PathVariable long segmentId,
                                                    @RequestBody UpdateClipSegmentBoundsRequest request) {
        if (request == null) {
            throw new DomainValidationException("缺少片段边界");
        }
        return ClipProjectResponse.from(clipProjectService.updateSegmentBounds(
                id, segmentId, request.startFrame(), request.endFrame()));
    }

    /**
     * 删除剪辑项目
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        clipProjectService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 查询帧索引状态
     * 索引未就绪时会触发后台建立，前端轮询本接口直到 READY
     */
    @GetMapping("/{id}/frame-index")
    public ClipFrameIndexStatusResponse frameIndexStatus(@PathVariable long id) {
        var detail = clipProjectService.detail(id);
        var project = detail.project();
        if (project.frameIndexStatus() != com.scoresync.domain.ClipFrameIndexStatus.READY) {
            frameIndexService.requestBuild(project);
            project = clipProjectService.detail(id).project();
        }
        return new ClipFrameIndexStatusResponse(project.frameIndexStatus().name(),
                project.totalFrames(), project.updatedAt());
    }

    /**
     * 查询全部帧在源视频中的时间
     * 一次取回完整映射，前端用二分查找换算位置，避免逐段请求
     */
    @GetMapping("/{id}/frame-times")
    public ClipAllFrameTimesResponse allFrameTimes(@PathVariable long id) {
        var project = clipProjectService.detail(id).project();
        var index = frameIndexService.requireIndex(project);
        long[] times = new long[index.frameCount()];
        for (int i = 0; i < times.length; i++) {
            times[i] = index.previewUs(i) / 1000L;
        }
        return new ClipAllFrameTimesResponse(index.frameCount(), times);
    }

    /**
     * 读取指定帧的缩略图
     * <p>
     * X-Thumbnail-Priority 标记为 background 时按后台预取处理，
     * 预取不会占满抽帧名额，当前帧始终优先
     */
    @GetMapping("/{id}/frames/{frameNo}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(@PathVariable long id,
                                            @PathVariable int frameNo,
                                            @RequestParam(value = "size", required = false) String size,
                                            @RequestHeader(value = "X-Thumbnail-Priority", required = false) String priority) {
        var detail = clipProjectService.detail(id);
        boolean foreground = !BACKGROUND_PRIORITY.equalsIgnoreCase(priority);
        ClipThumbnailSpec spec = thumbnailService.specFor(size);
        byte[] data = thumbnailService.thumbnail(detail.project(), frameNo, spec, foreground);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(spec.format().mediaType()));
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(data);
    }

    private List<ClipSegmentSpec> toSpecs(List<ClipSegmentRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new DomainValidationException("没有需要提交的片段");
        }
        return requests.stream()
                .map(request -> new ClipSegmentSpec(parseType(request.type()),
                        request.startFrame(), request.endFrame()))
                .toList();
    }

    private ClipSegmentType parseType(String value) {
        if (value == null) {
            throw new DomainValidationException("片段类型只能是 KEEP 或 DROP");
        }
        try {
            return ClipSegmentType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("片段类型只能是 KEEP 或 DROP");
        }
    }
}
