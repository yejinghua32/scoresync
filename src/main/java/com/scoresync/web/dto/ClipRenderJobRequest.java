package com.scoresync.web.dto;

/**
 * 创建剪辑导出任务的请求体
 * 请求体可以省略，省略时导出全部保留片段
 *
 * @param mode 导出范围，ALL 或 FEATURED
 */
public record ClipRenderJobRequest(String mode) {
}
