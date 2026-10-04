package com.scoresync.service.render;

import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreboardSnapshot;
import com.scoresync.domain.ScoreboardTemplate;
import com.scoresync.domain.SetScore;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 现代风格计分板渲染器
 * 实现深色背景、橙色强调色的现代 UI 风格计分牌，按 WTT 表格展示逐局比分
 */
@Component
public class ModernScoreboardRenderer implements ScoreboardTemplateRenderer {

    private static final Color BG = new Color(17, 24, 39, 230);
    private static final Color HEADER_BG = new Color(15, 23, 42, 190);
    private static final Color ACCENT = new Color(245, 158, 11);
    private static final Color ACCENT_DIM = new Color(245, 158, 11, 80);
    private static final Color WHITE = Color.WHITE;
    private static final Color MUTED = new Color(148, 163, 184);
    private static final Color ROW_BG = new Color(30, 41, 59, 200);
    private static final Color CURRENT_CELL_BG = new Color(51, 65, 85, 220);

    @Override
    public ScoreboardTemplate template() {
        return ScoreboardTemplate.MODERN;
    }

    @Override
    public BufferedImage render(ScoreboardSnapshot snapshot) {
        List<SetScore> sets = SetScoreLayout.visibleSets(snapshot.setScores());
        int frameWidth = snapshot.frameWidth();
        int width = SetScoreLayout.barWidth(frameWidth, sets.size());
        int height = RenderDimensions.barHeight(frameWidth, snapshot.frameHeight());

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int margin = SetScoreLayout.margin(frameWidth);
        int gap = SetScoreLayout.gap(frameWidth);
        int nameWidth = SetScoreLayout.nameWidth(frameWidth);
        int setWinsWidth = SetScoreLayout.setWinsWidth(frameWidth);
        int setColumnWidth = SetScoreLayout.setColumnWidth(frameWidth);
        int cornerRadius = Math.max(2, (int) Math.round(10 * SetScoreLayout.scale(frameWidth)));

        int headerHeight = Math.max(6, (int) Math.round(height * 0.22));
        int rowGap = Math.max(1, (int) Math.round(4 * SetScoreLayout.scale(frameWidth)));
        int rowHeight = Math.max(1, (height - headerHeight - rowGap) / 2);
        int arrowFontSize = Math.max(5, (int) (rowHeight * 0.44));

        int nameFontSize = Math.max(6, (int) (rowHeight * 0.44));
        int numberFontSize = Math.max(6, (int) (rowHeight * 0.50));
        int headerFontSize = Math.max(5, (int) (headerHeight * 0.60));

        Font nameFont = new Font("SansSerif", Font.BOLD, nameFontSize);
        Font numberFont = new Font("SansSerif", Font.BOLD, numberFontSize);
        Font headerFont = new Font("SansSerif", Font.BOLD, headerFontSize);
        Font arrowFont = new Font("SansSerif", Font.BOLD, arrowFontSize);

        PlayerSide servingSide = snapshot.matchCompleted()
                ? null
                : computeServingSide(snapshot.firstServer(),
                snapshot.setNumber(), snapshot.scoreA(), snapshot.scoreB());

        int currentSetNumber = sets.isEmpty() ? 0 : sets.get(sets.size() - 1).setNumber();
        int startX = margin;
        int winsX = startX + nameWidth + gap;
        int setsX = winsX + setWinsWidth + gap;

        g.setColor(HEADER_BG);
        g.fillRoundRect(startX, 0, width - margin * 2, headerHeight, cornerRadius, cornerRadius);
        SetScoreLayout.drawCellText(g, "胜局", headerFont, MUTED, winsX, 0, setWinsWidth, headerHeight);
        for (int i = 0; i < sets.size(); i++) {
            SetScore set = sets.get(i);
            boolean isCurrent = !snapshot.matchCompleted() && set.setNumber() == currentSetNumber;
            SetScoreLayout.drawCellText(g, String.valueOf(set.setNumber()), headerFont,
                    isCurrent ? ACCENT : MUTED,
                    setsX + i * (setColumnWidth + gap), 0, setColumnWidth, headerHeight);
        }

        int rowAY = headerHeight;
        int rowBY = headerHeight + rowHeight + rowGap;
        drawRow(g, snapshot.playerA(), String.valueOf(snapshot.setWinsA()), sets,
                startX, rowAY, rowHeight, gap, nameWidth, setWinsWidth, setColumnWidth,
                cornerRadius, arrowFontSize, arrowFont, nameFont, numberFont,
                servingSide != null && servingSide == PlayerSide.A, currentSetNumber, snapshot.matchCompleted(),
                false, PlayerSide.A);
        drawRow(g, snapshot.playerB(), String.valueOf(snapshot.setWinsB()), sets,
                startX, rowBY, rowHeight, gap, nameWidth, setWinsWidth, setColumnWidth,
                cornerRadius, arrowFontSize, arrowFont, nameFont, numberFont,
                servingSide != null && servingSide == PlayerSide.B, currentSetNumber, snapshot.matchCompleted(),
                true, PlayerSide.B);

        g.dispose();
        return image;
    }

    private void drawRow(Graphics2D g, String playerName, String setWins, List<SetScore> sets,
                          int startX, int y, int rowHeight, int gap,
                          int nameWidth, int setWinsWidth, int setColumnWidth,
                          int cornerRadius, int arrowFontSize, Font arrowFont, Font nameFont, Font numberFont,
                          boolean isServing, int currentSetNumber, boolean matchCompleted,
                          boolean flipOrder, PlayerSide rowSide) {
        // 名称区：箭头与名称
        g.setColor(ROW_BG);
        g.fillRoundRect(startX, y, nameWidth, rowHeight, cornerRadius, cornerRadius);
        int textLeft = startX;
        if (isServing) {
            int arrowX = flipOrder
                    ? startX + nameWidth - arrowFontSize - Math.max(2, gap)
                    : startX + Math.max(2, gap);
            g.setFont(arrowFont);
            g.setColor(ACCENT);
            int fmAscent = g.getFontMetrics().getAscent();
            g.drawString(flipOrder ? "◄" : "►", arrowX, y + rowHeight / 2 + fmAscent / 2 - Math.max(1, rowGapOffset(rowHeight)));
            textLeft = flipOrder
                    ? startX + Math.max(2, gap)
                    : startX + Math.max(2, gap) + arrowFontSize + gap;
        }
        SetScoreLayout.drawCellText(g, playerName, nameFont, WHITE,
                textLeft, y, startX + nameWidth - textLeft, rowHeight);

        // 胜局列
        int winsX = startX + nameWidth + gap;
        g.setColor(ACCENT);
        g.fillRoundRect(winsX, y, setWinsWidth, rowHeight, cornerRadius, cornerRadius);
        SetScoreLayout.drawCellText(g, setWins, numberFont, BG, winsX, y, setWinsWidth, rowHeight);

        // 逐局列
        int setsX = winsX + setWinsWidth + gap;
        for (int i = 0; i < sets.size(); i++) {
            SetScore set = sets.get(i);
            int x = setsX + i * (setColumnWidth + gap);
            boolean isCurrent = !matchCompleted && set.setNumber() == currentSetNumber;
            g.setColor(isCurrent ? CURRENT_CELL_BG : ROW_BG);
            g.fillRoundRect(x, y, setColumnWidth, rowHeight, cornerRadius, cornerRadius);
            if (isCurrent) {
                g.setColor(ACCENT_DIM);
                g.drawRoundRect(x, y, setColumnWidth, rowHeight, cornerRadius, cornerRadius);
            }
            int ownScore = rowSide == PlayerSide.A ? set.scoreA() : set.scoreB();
            String text = String.valueOf(ownScore);
            SetScoreLayout.drawCellText(g, text, numberFont, isCurrent ? ACCENT : WHITE,
                    x, y, setColumnWidth, rowHeight);
        }
    }

    private int rowGapOffset(int rowHeight) {
        return Math.max(1, (int) (rowHeight * 0.06));
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
