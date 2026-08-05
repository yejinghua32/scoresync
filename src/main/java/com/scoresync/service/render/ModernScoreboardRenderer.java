package com.scoresync.service.render;

import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreboardSnapshot;
import com.scoresync.domain.ScoreboardTemplate;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * 现代风格计分板渲染器
 * 实现深色背景、橙色强调色的现代UI风格计分板
 */
@Component
public class ModernScoreboardRenderer implements ScoreboardTemplateRenderer {

    private static final Color BG = new Color(17, 24, 39, 230);
    private static final Color ACCENT = new Color(245, 158, 11);
    private static final Color WHITE = Color.WHITE;
    private static final Color ROW_BG = new Color(30, 41, 59, 200);

    @Override
    public ScoreboardTemplate template() {
        return ScoreboardTemplate.MODERN;
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

        int outerMargin = (int) (8 * scale);
        int cornerRadius = (int) (8 * scale);
        int rowGap = (int) (4 * scale);
        int rowHeight = (height - rowGap) / 2;

        g.setColor(BG);
        g.fillRoundRect(outerMargin, outerMargin / 2, width - outerMargin * 2, height - outerMargin, cornerRadius, cornerRadius);

        int contentWidth = width - outerMargin * 2;
        int arrowWidth = (int) (contentWidth * 0.06);
        int nameWidth = (int) (contentWidth * 0.40);
        int bigScoreWidth = (int) (contentWidth * 0.16);
        int setWinsWidth = (int) (contentWidth * 0.10);
        int gap = (int) (4 * scale);

        int nameFontSize = (int) (rowHeight * 0.42);
        int bigScoreFontSize = (int) (rowHeight * 0.58);
        int setWinsFontSize = (int) (rowHeight * 0.42);

        Font nameFont = new Font("SansSerif", Font.BOLD, nameFontSize);
        Font bigScoreFont = new Font("SansSerif", Font.BOLD, bigScoreFontSize);
        Font setWinsFont = new Font("SansSerif", Font.BOLD, setWinsFontSize);

        PlayerSide servingSide = snapshot.matchCompleted()
                ? null
                : computeServingSide(snapshot.firstServer(),
                snapshot.setNumber(), snapshot.scoreA(), snapshot.scoreB());

        int startX = outerMargin;
        int row1Y = outerMargin / 2 + (int) (4 * scale);
        int row2Y = row1Y + rowHeight + rowGap;

        drawRow(g, startX, row1Y, rowHeight, arrowWidth, nameWidth, bigScoreWidth, setWinsWidth, gap,
                cornerRadius, snapshot.playerA(),
                String.valueOf(snapshot.scoreA()), String.valueOf(snapshot.setWinsA()),
                nameFont, bigScoreFont, setWinsFont,
                servingSide != null && servingSide == PlayerSide.A, false);

        drawRow(g, startX, row2Y, rowHeight, arrowWidth, nameWidth, bigScoreWidth, setWinsWidth, gap,
                cornerRadius, snapshot.playerB(),
                String.valueOf(snapshot.scoreB()), String.valueOf(snapshot.setWinsB()),
                nameFont, bigScoreFont, setWinsFont,
                servingSide != null && servingSide == PlayerSide.B, true);

        g.dispose();
        return image;
    }

    /**
     * 绘制单行计分板（包含选手名、局分、总分和发球指示）
     */
    private void drawRow(Graphics2D g, int x, int y, int rowHeight,
                         int arrowWidth, int nameWidth, int bigScoreWidth, int setWinsWidth, int gap,
                         int cornerRadius,
                         String playerName, String bigScore, String setWins,
                         Font nameFont, Font bigScoreFont, Font setWinsFont,
                         boolean isServing, boolean flipOrder) {
        int cursor = x;

        if (isServing) {
            int arrowFontSize = (int) (rowHeight * 0.5);
            g.setFont(new Font("SansSerif", Font.BOLD, arrowFontSize));
            g.setColor(ACCENT);
            g.drawString("►", cursor + (int) (4 * ((double) rowHeight / 20)), y + rowHeight / 2 + arrowFontSize / 3);
        }
        cursor += arrowWidth;

        g.setColor(ROW_BG);
        g.fillRoundRect(cursor, y, nameWidth, rowHeight, cornerRadius, cornerRadius);
        drawTextCentered(g, playerName, nameFont, WHITE, cursor, y, nameWidth, rowHeight);
        cursor += nameWidth + gap;

        g.setColor(ROW_BG);
        g.fillRoundRect(cursor, y, setWinsWidth, rowHeight, cornerRadius, cornerRadius);
        drawTextCentered(g, setWins, setWinsFont, WHITE, cursor, y, setWinsWidth, rowHeight);
        cursor += setWinsWidth + gap;

        g.setColor(ACCENT);
        g.fillRoundRect(cursor, y, bigScoreWidth, rowHeight, cornerRadius, cornerRadius);
        drawTextCentered(g, bigScore, bigScoreFont, WHITE, cursor, y, bigScoreWidth, rowHeight);
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
