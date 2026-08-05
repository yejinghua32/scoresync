package com.scoresync.web;

/**
 * 媒体流控制器
 * 负责视频文件的流式传输，支持 HTTP Range 请求以实现断点续传
 */

import com.scoresync.domain.VideoAsset;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.service.MediaStreamingService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping("/api")
public class MediaController {

    private final MediaStreamingService mediaStreamingService;
    private final VideoAssetRepository assetRepository;

    public MediaController(MediaStreamingService mediaStreamingService, VideoAssetRepository assetRepository) {
        this.mediaStreamingService = mediaStreamingService;
        this.assetRepository = assetRepository;
    }

    /**
     * 流式传输视频内容，支持 Range 头实现断点续传
     */
    @GetMapping("/media/{videoId}")
    public ResponseEntity<Resource> streamMedia(@PathVariable long videoId,
                                                @RequestHeader(value = "Range", required = false) String rangeHeader) {
        VideoAsset asset = assetRepository.findById(videoId);
        MediaStreamingService.StreamContent content = mediaStreamingService.open(videoId, rangeHeader);

        HttpHeaders headers = new HttpHeaders();
        String extension = getExtension(asset.absolutePath());
        headers.setContentType(getMimeType(extension));
        headers.set("Accept-Ranges", "bytes");

        // 流式传输：使用 InputStreamResource 边读边发，避免将整个大文件读入内存导致 OOM
        InputStreamResource resource = new InputStreamResource(content.body());

        if (rangeHeader == null || rangeHeader.isEmpty()) {
            headers.setContentLength(content.length());
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);
        } else {
            headers.setContentLength(content.length());
            headers.set("Content-Range", "bytes " + content.start() + "-" + content.end() + "/" + content.totalLength());
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .headers(headers)
                    .body(resource);
        }
    }

    private String getExtension(String absolutePath) {
        int lastDot = absolutePath.lastIndexOf('.');
        if (lastDot > 0) {
            return absolutePath.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private MediaType getMimeType(String extension) {
        return switch (extension.toLowerCase()) {
            case "mp4" -> MediaType.parseMediaType("video/mp4");
            case "webm" -> MediaType.parseMediaType("video/webm");
            case "mov" -> MediaType.parseMediaType("video/quicktime");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
