package com.scoresync.service.render;

import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreboardSnapshot;
import com.scoresync.domain.ScoreboardTemplate;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * 经典风格计分板渲染器
 * 实现黑黄配色的经典乒乓球计分板样式
 */
@Component
public class ClassicScoreboardRenderer implements ScoreboardTemplateRenderer {

    private static final Color BLACK_BG = new Color(0, 0, 0, 235);
    private static final Color YELLOW_BG = new Color(245, 190, 0);
    private static final Color WHITE = Color.WHITE;
    private static final Color YELLOW_DOT = new Color(255, 210, 0);

    @Override
    public ScoreboardTemplate template() {
        return ScoreboardTemplate.CLASSIC;
    }

    @Override
    public BufferedImage render(ScoreboardSnapshot snapshot) {
        double scale = snapshot.frameWidth() / 1920.0;
        int width = RenderDimensions.barWidth(snapshot.frameWidth());
        int height = RenderDimensions.barHeight(snapshot.frameWidth(), snapshot.frameHeight());

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int rowGap = (int) Math.max(2, 4 * scale);
        int rowHeight = (height - rowGap) / 2;
        int gap = (int) Math.max(2, 6 * scale);
        int cornerRadius = (int) Math.max(2, 6 * scale);
        int outerMargin = (int) (4 * scale);

        int nameWidth = (int) (width * 0.42);
        int bigScoreWidth = (int) (width * 0.12);
        int smallScoreWidth = (int) (width * 0.08);
        int dotSize = (int) (rowHeight * 0.45);

        int nameFontSize = (int) (rowHeight * 0.50);
        int bigScoreFontSize = (int) (rowHeight * 0.62);
        int smallScoreFontSize = (int) (rowHeight * 0.50);

        Font nameFont = new Font("SansSerif", Font.BOLD, nameFontSize);
        Font bigScoreFont = new Font("SansSerif", Font.BOLD, bigScoreFontSize);
        Font smallScoreFont = new Font("SansSerif", Font.BOLD, smallScoreFontSize);

        PlayerSide servingSide = snapshot.matchCompleted()
                ? null
                : computeServingSide(snapshot.firstServer(),
                snapshot.setNumber(), snapshot.scoreA(), snapshot.scoreB());

        int startX = outerMargin;

        drawRow(g, startX, 0, rowHeight, nameWidth, bigScoreWidth, smallScoreWidth, dotSize,
                gap, cornerRadius,
                snapshot.playerA(), String.valueOf(snapshot.setWinsA()), String.valueOf(snapshot.scoreA()),
                nameFont, bigScoreFont, smallScoreFont, servingSide != null && servingSide == PlayerSide.A);

        drawRow(g, startX, rowHeight + rowGap, rowHeight, nameWidth, bigScoreWidth, smallScoreWidth, dotSize,
                gap, cornerRadius,
                snapshot.playerB(), String.valueOf(snapshot.setWinsB()), String.valueOf(snapshot.scoreB()),
                nameFont, bigScoreFont, smallScoreFont, servingSide != null && servingSide == PlayerSide.B);

        g.dispose();
        return image;
    }

    /**
     * 绘制单行计分板（包含选手名、局分、总分和发球指示）
     */
    private void drawRow(Graphics2D g, int x, int y, int rowHeight,
                         int nameWidth, int bigScoreWidth, int smallScoreWidth, int dotSize,
                         int gap, int cornerRadius,
                         String playerName, String bigScore, String smallScore,
                         Font nameFont, Font bigScoreFont, Font smallScoreFont,
                         boolean isServing) {
        int cursor = x;

        g.setColor(BLACK_BG);
        g.fillRoundRect(cursor, y, nameWidth, rowHeight, cornerRadius, cornerRadius);
        drawTextCentered(g, playerName, nameFont, WHITE, cursor, y, nameWidth, rowHeight);
        cursor += nameWidth + gap;

        g.setColor(YELLOW_BG);
        g.fillRoundRect(cursor, y, bigScoreWidth, rowHeight, cornerRadius, cornerRadius);
        drawTextCentered(g, bigScore, bigScoreFont, WHITE, cursor, y, bigScoreWidth, rowHeight);
        cursor += bigScoreWidth + gap;

        g.setColor(BLACK_BG);
        g.fillRoundRect(cursor, y, smallScoreWidth, rowHeight, cornerRadius, cornerRadius);
        drawTextCentered(g, smallScore, smallScoreFont, WHITE, cursor, y, smallScoreWidth, rowHeight);
        cursor += smallScoreWidth + gap;

        if (isServing) {
            g.setColor(YELLOW_DOT);
            int dotY = y + (rowHeight - dotSize) / 2;
            g.fillOval(cursor, dotY, dotSize, dotSize);
        }
    }

    /**
     * 居中绘制文本
     */
    private void drawTextCentered(Graphics2D g, String text, Font font, Color color,
                                  int x, int y, int w, int h) {
        if (text == null || text.isEmpty()) return;
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textX = x + (w - fm.stringWidth(text)) / 2;
        int textY = y + (h - fm.getHeight()) / 2 + fm.getAscent();
        g.setColor(color);
        g.drawString(text, textX, textY);
    }

    /**
     * 计算当前发球方
     * 乒乓球发球规则：每2分换发球，奇数局后换发球方起始
     */
    private PlayerSide computeServingSide(PlayerSide firstServer, int setNo,
                                          int scoreA, int scoreB) {
        PlayerSide setStarter = (setNo % 2 == 1) ? firstServer : firstServer.other();
        int totalPoints = scoreA + scoreB;
        boolean deuce = scoreA >= 10 && scoreB >= 10;
        int serverIndex = deuce ? (totalPoints % 2) : ((totalPoints / 2) % 2);
        return serverIndex == 0 ? setStarter : setStarter.other();
    }
}
