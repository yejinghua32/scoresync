package com.scoresync.domain;

/**
 * 比分牌模板配置
 * 比分牌的视觉样式配置，包含名称、透明度及颜色设置
 *
 * @param id        唯一标识符（可为 null 表示新配置）
 * @param name      配置名称
 * @param opacity   透明度（0-100）
 * @param bgColor   背景颜色（十六进制，如 "#000000"）
 * @param textColor 文字颜色（十六进制）
 */
public record ScoreboardTemplateConfig(
        Long id,
        String name,
        int opacity,
        String bgColor,
        String textColor
) {
}
