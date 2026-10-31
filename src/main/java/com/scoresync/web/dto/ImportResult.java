package com.scoresync.web.dto;

/**
 * 导入备份的结果
 *
 * @param projectId    新建项目ID
 * @param videoMissing 备份中的原视频不在视频库中，项目需先替换视频才能编辑
 */
public record ImportResult(long projectId, boolean videoMissing) {
}
