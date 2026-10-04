package com.scoresync.service.render;

import com.scoresync.domain.SetScore;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.List;

/**
 * 比分牌逐局表格的共享布局
 * 集中计算最近 7 局窗口与各列宽度，保证经典与现代模板
 * 使用同一份窗口和宽度算法，仅配色与强调方式不同。
 */
final class SetScoreLayout {

    /** 最多展示的局数（显示最近 7 局） */
    static final int MAX_VISIBLE_SETS = 7;

    /** 1920 宽基准下各部分占视频帧宽度的比例 */
    private static final double MARGIN_RATIO = 8 / 1920.0;
    private static final double NAME_RATIO = 260 / 1920.0;
    private static final double SERVE_RATIO = 30 / 1920.0;
    private static final double SET_WINS_RATIO = 64 / 1920.0;
    private static final double SET_COLUMN_RATIO = 56 / 1920.0;
    private static final double GAP_RATIO = 6 / 1920.0;

    private SetScoreLayout() {
    }

    /**
     * 取最近 MAX_VISIBLE_SETS 局
     *
     * @param setScores 截至该时刻的逐局比分（按局号升序）
     * @return 可见局列表，保留真实局号
     */
    static List<SetScore> visibleSets(List<SetScore> setScores) {
        if (setScores == null || setScores.isEmpty()) {
            return List.of();
        }
        if (setScores.size() <= MAX_VISIBLE_SETS) {
            return setScores;
        }
        return List.copyOf(setScores.subList(setScores.size() - MAX_VISIBLE_SETS, setScores.size()));
    }

    /**
     * 归一化可见局数到 1..MAX_VISIBLE_SETS
     */
    static int normalizedSetCount(int setCount) {
        return Math.max(1, Math.min(MAX_VISIBLE_SETS, setCount));
    }

    /**
     * 计算整体宽度：边距 + 名称区 + 发球列 + 胜局列 + 可见局数列 + 间距
     *
     * <p>宽度与视频帧宽度严格成正比，因此同一局数下不同分辨率的比分牌
     * 占屏比例一致；小分辨率下由 {@link #drawCellText} 缩小字号或省略，
     * 不依赖列宽下限，避免破坏比例。
     *
     * @param frameWidth 视频帧宽度
     * @param setCount   可见局数
     * @return 比分牌宽度（像素），随局数增加向右扩展
     */
    static int barWidth(int frameWidth, int setCount) {
        int sets = normalizedSetCount(setCount);
        double ratio = MARGIN_RATIO * 2
                + NAME_RATIO
                + SERVE_RATIO
                + SET_WINS_RATIO
                + SET_COLUMN_RATIO * sets
                + GAP_RATIO * (sets + 3);
        return Math.max(1, (int) Math.round(frameWidth * ratio));
    }

    /**
     * 名称区宽度
     */
    static int nameWidth(int frameWidth) {
        return scaled(frameWidth, NAME_RATIO);
    }

    /**
     * 发球标识列宽度
     *
     * <p>固定宽度且始终占位，保证发球方切换时胜局列和逐局列不会左右跳动。
     */
    static int serveColumnWidth(int frameWidth) {
        return scaled(frameWidth, SERVE_RATIO);
    }

    /**
     * 胜局列宽度
     */
    static int setWinsWidth(int frameWidth) {
        return scaled(frameWidth, SET_WINS_RATIO);
    }

    /**
     * 单个逐局列宽度
     */
    static int setColumnWidth(int frameWidth) {
        return scaled(frameWidth, SET_COLUMN_RATIO);
    }

    /**
     * 列间距
     */
    static int gap(int frameWidth) {
        return scaled(frameWidth, GAP_RATIO);
    }

    /**
     * 外边距
     */
    static int margin(int frameWidth) {
        return scaled(frameWidth, MARGIN_RATIO);
    }

    /**
     * 相对 1920 基准的缩放系数
     */
    static double scale(int frameWidth) {
        return frameWidth / 1920.0;
    }

    private static int scaled(int frameWidth, double ratio) {
        return Math.max(1, (int) Math.round(frameWidth * ratio));
    }

    /**
     * 在单元格内居中绘制单行文本。
     * 字号按可用宽度等比缩小；缩到下限仍放不下时以省略号结尾，避免溢出到相邻列。
     */
    static void drawCellText(Graphics2D g, String text, Font baseFont, Color color,
                              int x, int y, int w, int h) {
        if (text == null || text.isEmpty() || w <= 0 || h <= 0) {
            return;
        }
        Font font = fitFont(g, text, baseFont, w);
        FontMetrics fm = g.getFontMetrics(font);
        String shown = text;
        if (fm.stringWidth(shown) > w) {
            shown = ellipsize(g, text, font, w);
            fm = g.getFontMetrics(font);
        }
        int textX = x + Math.max(0, (w - fm.stringWidth(shown)) / 2);
        int textY = y + (h - fm.getHeight()) / 2 + fm.getAscent();
        g.setFont(font);
        g.setColor(color);
        g.drawString(shown, textX, textY);
    }

    private static Font fitFont(Graphics2D g, String text, Font baseFont, int availableWidth) {
        int size = baseFont.getSize();
        if (g.getFontMetrics(baseFont).stringWidth(text) <= availableWidth) {
            return baseFont;
        }
        int minSize = Math.max(1, (int) Math.round(baseFont.getSize() * 0.6));
        while (size > minSize) {
            Font candidate = baseFont.deriveFont((float) size);
            if (g.getFontMetrics(candidate).stringWidth(text) <= availableWidth) {
                return candidate;
            }
            size--;
        }
        return baseFont.deriveFont((float) minSize);
    }

    private static String ellipsize(Graphics2D g, String text, Font font, int availableWidth) {
        String suffix = "…";
        FontMetrics fm = g.getFontMetrics(font);
        if (fm.stringWidth(suffix) >= availableWidth) {
            return "";
        }
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + fm.stringWidth(suffix) > availableWidth) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }
}
