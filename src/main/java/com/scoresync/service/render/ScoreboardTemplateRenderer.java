package com.scoresync.service.render;

import com.scoresync.domain.ScoreboardSnapshot;
import com.scoresync.domain.ScoreboardTemplate;

import java.awt.image.BufferedImage;

/**
 * 计分板模板渲染器接口
 * 定义将 ScoreboardSnapshot 渲染为 BufferedImage 的能力
 */
public interface ScoreboardTemplateRenderer {
    /**
     * 返回该渲染器对应的模板类型
     */
    ScoreboardTemplate template();

    /**
     * 将指定的计分板快照渲染为图片
     *
     * @param snapshot 包含当前比分、选手信息等的快照数据
     * @return 渲染后的计分板图片
     */
    BufferedImage render(ScoreboardSnapshot snapshot);
}
