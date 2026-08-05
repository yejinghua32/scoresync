package com.scoresync.web;

/**
 * 视频源管理控制器
 * 负责管理视频扫描目录和本地视频文件登记
 */

import com.scoresync.domain.VideoAsset;
import com.scoresync.domain.VideoSource;
import com.scoresync.service.NativeFilePickerService;
import com.scoresync.service.VideoLibraryService;
import com.scoresync.web.dto.CreateVideoSourceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api")
public class VideoSourceController {

    private final VideoLibraryService videoLibraryService;
    private final NativeFilePickerService filePickerService;

    public VideoSourceController(VideoLibraryService videoLibraryService, NativeFilePickerService filePickerService) {
        this.videoLibraryService = videoLibraryService;
        this.filePickerService = filePickerService;
    }

    /**
     * 获取所有视频源列表
     */
    @GetMapping("/video-sources")
    public List<VideoSource> listSources() {
        return videoLibraryService.listSources();
    }

    /**
     * 添加新的视频扫描目录
     */
    @PostMapping("/video-sources")
    public ResponseEntity<VideoSource> addSource(@Valid @RequestBody CreateVideoSourceRequest request) {
        VideoSource source = videoLibraryService.addSource(request.rootPath());
        return ResponseEntity.status(HttpStatus.CREATED).body(source);
    }

    /**
     * 删除指定视频源（同时取消登记该目录下的所有视频）
     */
    @DeleteMapping("/video-sources/{id}")
    public ResponseEntity<Void> removeSource(@PathVariable long id) {
        videoLibraryService.removeSource(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取所有已登记的视频文件
     */
    @GetMapping("/videos")
    public List<VideoAsset> listAssets() {
        return videoLibraryService.listAssets();
    }

    /**
     * 扫描指定视频源目录，登记新发现的视频文件
     */
    @PostMapping("/video-sources/{id}/scan")
    public List<VideoAsset> scan(@PathVariable long id) {
        return videoLibraryService.scan(id);
    }

    /**
     * 打开系统文件选择器，选择本地视频文件并登记
     */
    @PostMapping("/videos/pick-local")
    public ResponseEntity<VideoAsset> pickLocal() {
        Path path = filePickerService.chooseVideoFile()
                .orElseThrow(() -> new com.scoresync.support.DomainValidationException("未选择文件"));
        VideoAsset asset = videoLibraryService.registerPicked(path);
        return ResponseEntity.status(HttpStatus.CREATED).body(asset);
    }
}
