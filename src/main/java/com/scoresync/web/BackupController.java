package com.scoresync.web;

/**
 * 备份与恢复控制器
 * 负责项目数据的导出（JSON格式）和导入
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scoresync.domain.EditorState;
import com.scoresync.service.BackupService;
import com.scoresync.web.dto.BackupDocument;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api")
public class BackupController {

    private final BackupService backupService;
    private final ObjectMapper objectMapper;

    public BackupController(BackupService backupService, ObjectMapper objectMapper) {
        this.backupService = backupService;
        this.objectMapper = objectMapper;
    }

    /**
     * 导出项目备份（包含项目信息、视频信息、得分事件）
     */
    @GetMapping("/projects/{id}/backup")
    public ResponseEntity<byte[]> downloadBackup(@PathVariable long id) throws IOException {
        BackupDocument backup = backupService.exportProject(id);

        String filename = "scoresync-project-" + id + ".json";
        byte[] jsonBytes = objectMapper.writeValueAsBytes(backup);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDispositionFormData("attachment", filename);

        return new ResponseEntity<>(jsonBytes, headers, HttpStatus.OK);
    }

    /**
     * 导入项目备份（需视频文件元信息匹配）
     */
    @PostMapping("/projects/import")
    public ResponseEntity<EditorState> importBackup(@RequestBody BackupDocument document) {
        EditorState state = backupService.importDocument(document);
        return ResponseEntity.status(HttpStatus.CREATED).body(state);
    }
}
