package com.scoresync.service.render;

/**
 * 渲染尺寸计算工具类
 * 根据视频帧尺寸计算计分板条的最佳宽高
 */
final class RenderDimensions {

    private static final double BAR_HEIGHT_RATIO = 0.123;
    private static final int MIN_BAR_HEIGHT = 48;
    private static final double BAR_WIDTH_RATIO = 0.3;

    private RenderDimensions() {
    }

    /**
     * 计算计分板条的高度
     *
     * @param frameWidth  视频帧宽度
     * @param frameHeight 视频帧高度
     * @return 计分板条高度（像素）
     */
    static int barHeight(int frameWidth, int frameHeight) {
        return Math.max(MIN_BAR_HEIGHT, Math.round(frameHeight * (float) BAR_HEIGHT_RATIO));
    }

    /**
     * 计算计分板条的宽度
     *
     * @param frameWidth 视频帧宽度
     * @return 计分板条宽度（像素）
     */
    static int barWidth(int frameWidth) {
        return Math.round(frameWidth * (float) BAR_WIDTH_RATIO);
    }
}
