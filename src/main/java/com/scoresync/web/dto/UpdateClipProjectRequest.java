package com.scoresync.web.dto;

import jakarta.validation.constraints.Positive;

/**
 * 更新剪辑项目配置的请求体
 * 名称与步长均可省略，省略的字段保持原值
 */
public record UpdateClipProjectRequest(
        String name,
        @Positive Integer largeStep,
        @Positive Integer smallStep) {
}
