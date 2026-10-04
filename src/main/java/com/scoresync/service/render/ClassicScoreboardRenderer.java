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
 * 经典风格计分板渲染器
 * 实现黑黄配色的经典乒乓球计分牌样式，按 WTT 表格展示逐局比分
 */
@Component
public class ClassicScoreboardRenderer implements ScoreboardTemplateRenderer {

    private static final Color BLACK_BG = new Color(0, 0, 0, 235);
    private static final Color HEADER_BG = new Color(0, 0, 0, 160);
    private static final Color YELLOW_BG = new Color(245, 190, 0);
    private static final Color WINS_BG = new Color(211, 47, 47);
    private static final Color WHITE = Color.WHITE;
    private static final Color YELLOW_DOT = new Color(255, 210, 0);
    private static final Color GREY = new Color(170, 170, 170);

    @Override
    public ScoreboardTemplate template() {
        return ScoreboardTemplate.CLASSIC;
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
        int serveColumnWidth = SetScoreLayout.serveColumnWidth(frameWidth);
        int setWinsWidth = SetScoreLayout.setWinsWidth(frameWidth);
        int setColumnWidth = SetScoreLayout.setColumnWidth(frameWidth);
        int cornerRadius = Math.max(2, (int) Math.round(8 * SetScoreLayout.scale(frameWidth)));

        int headerHeight = Math.max(6, (int) Math.round(height * 0.22));
        int rowGap = Math.max(1, (int) Math.round(3 * SetScoreLayout.scale(frameWidth)));
        int rowHeight = Math.max(1, (height - headerHeight - rowGap) / 2);
        int dotSize = Math.max(2, (int) (rowHeight * 0.32));

        int nameFontSize = Math.max(6, (int) (rowHeight * 0.46));
        int numberFontSize = Math.max(6, (int) (rowHeight * 0.52));
        int headerFontSize = Math.max(5, (int) (headerHeight * 0.62));

        Font nameFont = new Font("SansSerif", Font.BOLD, nameFontSize);
        Font numberFont = new Font("SansSerif", Font.BOLD, numberFontSize);
        // 当前局比分使用更大字号，配合强调色底形成更强的视觉重心
        Font currentScoreFont = new Font("SansSerif", Font.BOLD,
                Math.max(numberFontSize, (int) (rowHeight * 0.72)));
        Font headerFont = new Font("SansSerif", Font.BOLD, headerFontSize);

        PlayerSide servingSide = snapshot.matchCompleted()
                ? null
                : computeServingSide(snapshot.firstServer(),
                snapshot.setNumber(), snapshot.scoreA(), snapshot.scoreB());

        int currentSetNumber = sets.isEmpty() ? 0 : sets.get(sets.size() - 1).setNumber();

        // 表头：名称列留空，其余为“胜局”与各局号
        int headerX = margin;
        int headerServeX = headerX + nameWidth + gap;
        int headerWinsX = headerServeX + serveColumnWidth + gap;
        int headerSetsX = headerWinsX + setWinsWidth + gap;
        g.setColor(HEADER_BG);
        g.fillRect(headerX, 0, width - margin * 2, headerHeight);
        SetScoreLayout.drawCellText(g, "胜局", headerFont, GREY, headerWinsX, 0, setWinsWidth, headerHeight);
        for (int i = 0; i < sets.size(); i++) {
            SetScore set = sets.get(i);
            boolean isCurrent = !snapshot.matchCompleted() && set.setNumber() == currentSetNumber;
            SetScoreLayout.drawCellText(g, String.valueOf(set.setNumber()), headerFont,
                    isCurrent ? YELLOW_BG : GREY,
                    headerSetsX + i * (setColumnWidth + gap), 0, setColumnWidth, headerHeight);
        }

        int rowAY = headerHeight;
        int rowBY = headerHeight + rowHeight + rowGap;
        drawRow(g, snapshot.playerA(), String.valueOf(snapshot.setWinsA()), sets,
                headerX, rowAY, rowHeight, gap, nameWidth, serveColumnWidth, setWinsWidth, setColumnWidth,
                cornerRadius, dotSize, nameFont, numberFont, currentScoreFont,
                servingSide != null && servingSide == PlayerSide.A, currentSetNumber, snapshot.matchCompleted(),
                PlayerSide.A);
        drawRow(g, snapshot.playerB(), String.valueOf(snapshot.setWinsB()), sets,
                headerX, rowBY, rowHeight, gap, nameWidth, serveColumnWidth, setWinsWidth, setColumnWidth,
                cornerRadius, dotSize, nameFont, numberFont, currentScoreFont,
                servingSide != null && servingSide == PlayerSide.B, currentSetNumber, snapshot.matchCompleted(),
                PlayerSide.B);

        g.dispose();
        return image;
    }

    private void drawRow(Graphics2D g, String playerName, String setWins, List<SetScore> sets,
                          int startX, int y, int rowHeight, int gap,
                          int nameWidth, int serveColumnWidth, int setWinsWidth, int setColumnWidth,
                          int cornerRadius, int dotSize,
                          Font nameFont, Font numberFont, Font currentScoreFont,
                          boolean isServing, int currentSetNumber, boolean matchCompleted,
                          PlayerSide rowSide) {
        // 名称区
        g.setColor(BLACK_BG);
        g.fillRoundRect(startX, y, nameWidth, rowHeight, cornerRadius, cornerRadius);
        SetScoreLayout.drawCellText(g, playerName, nameFont, WHITE,
                startX, y, nameWidth, rowHeight);

        // 发球标识列：固定宽度，背景与队名一致，发球圆点居中
        int serveX = startX + nameWidth + gap;
        g.setColor(BLACK_BG);
        g.fillRoundRect(serveX, y, serveColumnWidth, rowHeight, cornerRadius, cornerRadius);
        if (isServing) {
            g.setColor(YELLOW_DOT);
            int dotY = y + (rowHeight - dotSize) / 2;
            int dotX = serveX + (serveColumnWidth - dotSize) / 2;
            g.fillOval(dotX, dotY, dotSize, dotSize);
        }

        // 胜局列：红底白字
        int winsX = serveX + serveColumnWidth + gap;
        g.setColor(WINS_BG);
        g.fillRoundRect(winsX, y, setWinsWidth, rowHeight, cornerRadius, cornerRadius);
        SetScoreLayout.drawCellText(g, setWins, numberFont, WHITE, winsX, y, setWinsWidth, rowHeight);

        // 逐局列
        int setsX = winsX + setWinsWidth + gap;
        for (int i = 0; i < sets.size(); i++) {
            SetScore set = sets.get(i);
            int x = setsX + i * (setColumnWidth + gap);
            boolean isCurrent = !matchCompleted && set.setNumber() == currentSetNumber;
            int ownScore = rowSide == PlayerSide.A ? set.scoreA() : set.scoreB();
            String text = String.valueOf(ownScore);
            if (isCurrent) {
                // 当前局：黄底白字
                g.setColor(YELLOW_BG);
                g.fillRoundRect(x, y, setColumnWidth, rowHeight, cornerRadius, cornerRadius);
                SetScoreLayout.drawCellText(g, text, currentScoreFont, WHITE,
                        x, y, setColumnWidth, rowHeight);
            } else {
                g.setColor(BLACK_BG);
                g.fillRoundRect(x, y, setColumnWidth, rowHeight, cornerRadius, cornerRadius);
                SetScoreLayout.drawCellText(g, text, numberFont, WHITE,
                        x, y, setColumnWidth, rowHeight);
            }
        }
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
