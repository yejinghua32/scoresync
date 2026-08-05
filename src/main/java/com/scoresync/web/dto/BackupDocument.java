package com.scoresync.web.dto;

/**
 * 备份文档（用于项目导出/导入的 JSON 格式）
 * 包含项目信息、视频信息、得分事件列表
 */

import java.util.List;

public record BackupDocument(
        int schemaVersion,
        BackupProject project,
        BackupVideo video,
        List<BackupEvent> events) {

    public record BackupProject(
            String name,
            long durationMs,
            String playerA,
            String playerB,
            int targetWins,
            String firstServer) {
    }

    public record BackupVideo(
            String displayName,
            long fileSize,
            long modifiedAt,
            String originType) {
    }

    public record BackupEvent(
            long videoTimeMs,
            String playerSide,
            long sequenceNo) {
    }
}
