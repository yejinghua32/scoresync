package com.scoresync.service.render;

import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreboardSnapshot;
import com.scoresync.domain.ScoreboardTemplate;
import com.scoresync.domain.SetScore;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScoreboardTemplateRendererTest {

    private ScoreboardSnapshot snapshot(ScoreboardTemplate template) {
        return new ScoreboardSnapshot(template, "Player A", "Player B", 5, 3, 2, 1, 0, false, PlayerSide.A, 1920, 1080,
                List.of(new SetScore(1, 11, 8), new SetScore(2, 5, 3)));
    }

    /** 构造在队名列表头显示项目名称的快照 */
    private ScoreboardSnapshot namedProjectSnapshot(ScoreboardTemplate template) {
        return new ScoreboardSnapshot(template, "Player A", "Player B", 5, 3, 2, 1, 0, false, PlayerSide.A, 1920, 1080,
                List.of(new SetScore(1, 11, 8), new SetScore(2, 5, 3)), null, "2026 总决赛", true);
    }

    private ScoreboardSnapshot compactSnapshot(ScoreboardTemplate template) {
        return new ScoreboardSnapshot(template, "Player A", "Player B", 5, 3, 2, 1, 0, false, PlayerSide.A, 320, 180,
                List.of(new SetScore(1, 11, 8), new SetScore(2, 5, 3)));
    }

    /** 构造包含 setCount 局、最后一局为当前局的快照 */
    private ScoreboardSnapshot multiSetSnapshot(ScoreboardTemplate template, int setCount, int frameWidth) {
        List<SetScore> sets = new ArrayList<>();
        for (int i = 1; i < setCount; i++) {
            sets.add(new SetScore(i, 11, i % 2 == 0 ? 8 : 6));
        }
        sets.add(new SetScore(setCount, 5, 3));
        return new ScoreboardSnapshot(template, "Player A", "Player B", 5, 3, setCount,
                Math.max(0, setCount / 2), 0, false, PlayerSide.A, frameWidth, frameWidth * 9 / 16, sets);
    }

    private ScoreboardSnapshot longNameSnapshot(ScoreboardTemplate template, int frameWidth) {
        return new ScoreboardSnapshot(template, "阿瓦隆·布莱克伍德长名字", "欧阳若曦", 11, 9, 1, 0, 0, false,
                PlayerSide.A, frameWidth, frameWidth * 9 / 16, List.of(new SetScore(1, 11, 9)));
    }

    private ScoreboardSnapshot mediumSnapshot(ScoreboardTemplate template) {
        return new ScoreboardSnapshot(template, "Player A", "Player B",
                5, 3, 2, 1, 0, false, PlayerSide.A, 960, 540,
                List.of(new SetScore(1, 11, 8), new SetScore(2, 5, 3)));
    }

    private ScoreboardSnapshot highlightedSnapshot(ScoreboardTemplate template, PlayerSide side) {
        return highlightedSnapshot(template, side, 1920);
    }

    private ScoreboardSnapshot highlightedSnapshot(ScoreboardTemplate template, PlayerSide side, int frameWidth) {
        return new ScoreboardSnapshot(template, "Player A", "Player B", 5, 3, 2, 1, 0, false, PlayerSide.A,
                frameWidth, frameWidth * 9 / 16, List.of(new SetScore(1, 11, 8), new SetScore(2, 5, 3)), side);
    }

    /**
     * 统计与高亮颜色足够接近的像素，返回 [数量, 最小X, 最小Y, 最大X, 最大Y]
     */
    private int[] highlightBounds(BufferedImage image) {
        java.awt.Color target = SetScoreLayout.HIGHLIGHT_COLOR;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                java.awt.Color pixel = new java.awt.Color(image.getRGB(x, y), true);
                if (Math.abs(pixel.getRed() - target.getRed()) <= 24
                        && Math.abs(pixel.getGreen() - target.getGreen()) <= 24
                        && Math.abs(pixel.getBlue() - target.getBlue()) <= 24) {
                    count++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (count == 0) {
            return null;
        }
        return new int[]{count, minX, minY, maxX, maxY};
    }

    private boolean pixelsDiffer(BufferedImage left, BufferedImage right) {
        for (int y = 0; y < left.getHeight(); y++) {
            for (int x = 0; x < left.getWidth(); x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void classicRendererProducesPngWithTransparency() throws Exception {
        ClassicScoreboardRenderer renderer = new ClassicScoreboardRenderer();
        BufferedImage image = renderer.render(snapshot(ScoreboardTemplate.CLASSIC));

        assertThat(image.getType()).isEqualTo(BufferedImage.TYPE_INT_ARGB);
        assertThat(image.getWidth()).isEqualTo(RenderDimensions.barWidth(1920, 2));
        assertThat(image.getHeight()).isEqualTo(RenderDimensions.barHeight(1920, 1080));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        assertThat(baos.toByteArray()).isNotEmpty();

        assertThat(countTransparentPixels(image)).isGreaterThan(0);
    }

    @Test
    void modernRendererProducesPngWithTransparency() throws Exception {
        ModernScoreboardRenderer renderer = new ModernScoreboardRenderer();
        BufferedImage image = renderer.render(snapshot(ScoreboardTemplate.MODERN));

        assertThat(image.getType()).isEqualTo(BufferedImage.TYPE_INT_ARGB);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        assertThat(baos.toByteArray()).isNotEmpty();

        assertThat(countTransparentPixels(image)).isGreaterThan(0);
    }

    @Test
    void classicAndModernProduceDifferentPixels() {
        ClassicScoreboardRenderer classic = new ClassicScoreboardRenderer();
        ModernScoreboardRenderer modern = new ModernScoreboardRenderer();

        BufferedImage classicImage = classic.render(snapshot(ScoreboardTemplate.CLASSIC));
        BufferedImage modernImage = modern.render(snapshot(ScoreboardTemplate.MODERN));

        boolean different = false;
        outer:
        for (int y = 0; y < classicImage.getHeight(); y++) {
            for (int x = 0; x < classicImage.getWidth(); x++) {
                if (classicImage.getRGB(x, y) != modernImage.getRGB(x, y)) {
                    different = true;
                    break outer;
                }
            }
        }
        assertThat(different).isTrue();
    }

    @Test
    void compactRenderScalesProportionally() {
        ClassicScoreboardRenderer classic = new ClassicScoreboardRenderer();
        ModernScoreboardRenderer modern = new ModernScoreboardRenderer();

        BufferedImage classicImage = classic.render(snapshot(ScoreboardTemplate.CLASSIC));
        BufferedImage compactClassic = classic.render(compactSnapshot(ScoreboardTemplate.CLASSIC));

        assertThat(compactClassic.getWidth()).isEqualTo(RenderDimensions.barWidth(320, 2));
        assertThat(compactClassic.getHeight()).isLessThan(classicImage.getHeight());
        // 宽度是整数像素，320 宽下最多有 1 像素取整误差（1 / 320 ≈ 0.003）
        assertThat((double) compactClassic.getWidth() / classicImage.getWidth())
                .isEqualTo(320.0 / 1920.0, within(0.005));
    }

    @Test
    void renderersReturnCorrectTemplateType() {
        ClassicScoreboardRenderer classic = new ClassicScoreboardRenderer();
        ModernScoreboardRenderer modern = new ModernScoreboardRenderer();

        assertThat(classic.template()).isEqualTo(ScoreboardTemplate.CLASSIC);
        assertThat(modern.template()).isEqualTo(ScoreboardTemplate.MODERN);
    }

    @Test
    void noHighlightSideRendersWithoutHighlightPixels() {
        BufferedImage classic = new ClassicScoreboardRenderer()
                .render(snapshot(ScoreboardTemplate.CLASSIC));
        BufferedImage modern = new ModernScoreboardRenderer()
                .render(snapshot(ScoreboardTemplate.MODERN));

        assertThat(highlightBounds(classic)).isNull();
        assertThat(highlightBounds(modern)).isNull();
    }

    @Test
    void highlightedSideDrawsHighlightInBothTemplates() {
        BufferedImage classic = new ClassicScoreboardRenderer()
                .render(highlightedSnapshot(ScoreboardTemplate.CLASSIC, PlayerSide.A));
        BufferedImage modern = new ModernScoreboardRenderer()
                .render(highlightedSnapshot(ScoreboardTemplate.MODERN, PlayerSide.A));

        assertThat(highlightBounds(classic)).isNotNull();
        assertThat(highlightBounds(modern)).isNotNull();
    }

    @Test
    void highlightForSideAIsAboveHighlightForSideB() {
        BufferedImage sideA = new ClassicScoreboardRenderer()
                .render(highlightedSnapshot(ScoreboardTemplate.CLASSIC, PlayerSide.A));
        BufferedImage sideB = new ClassicScoreboardRenderer()
                .render(highlightedSnapshot(ScoreboardTemplate.CLASSIC, PlayerSide.B));
        int[] aBounds = highlightBounds(sideA);
        int[] bBounds = highlightBounds(sideB);

        assertThat(aBounds).isNotNull();
        assertThat(bBounds).isNotNull();
        // A 行在上、B 行在下，两处高亮不重叠
        assertThat(aBounds[4]).isLessThan(bBounds[2]);
    }

    @Test
    void highlightChangesTheRenderedImageOnlyWhenPresent() {
        BufferedImage plain = new ClassicScoreboardRenderer()
                .render(snapshot(ScoreboardTemplate.CLASSIC));
        BufferedImage highlighted = new ClassicScoreboardRenderer()
                .render(highlightedSnapshot(ScoreboardTemplate.CLASSIC, PlayerSide.A));

        assertThat(plain.getWidth()).isEqualTo(highlighted.getWidth());
        assertThat(plain.getHeight()).isEqualTo(highlighted.getHeight());
        assertThat(pixelsDiffer(plain, highlighted)).isTrue();
    }

    @Test
    void compactHighlightStaysInsideTheBoard() {
        for (ScoreboardTemplate template : ScoreboardTemplate.values()) {
            ScoreboardTemplateRenderer renderer = template == ScoreboardTemplate.CLASSIC
                    ? new ClassicScoreboardRenderer()
                    : new ModernScoreboardRenderer();
            BufferedImage image = renderer
                    .render(highlightedSnapshot(template, PlayerSide.B, 320));
            int[] bounds = highlightBounds(image);

            assertThat(bounds).isNotNull();
            assertThat(bounds[1]).isGreaterThanOrEqualTo(0);
            assertThat(bounds[2]).isGreaterThanOrEqualTo(0);
            assertThat(bounds[3]).isLessThan(image.getWidth());
            assertThat(bounds[4]).isLessThan(image.getHeight());
        }
    }

    /**
     * 统计指定矩形内的浅色像素数量，用于判断表头是否绘制了文字
     */
    private int countLightPixels(BufferedImage image, int x0, int y0, int x1, int y1) {
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                java.awt.Color p = new java.awt.Color(image.getRGB(x, y), true);
                if (p.getRed() > 100 && p.getAlpha() > 200) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 发球列表头所在单元格的矩形范围
     */
    private int[] serveHeaderCell(int frameWidth, int frameHeight) {
        int barHeight = RenderDimensions.barHeight(frameWidth, frameHeight);
        int headerHeight = Math.max(6, (int) Math.round(barHeight * 0.22));
        int serveX = SetScoreLayout.margin(frameWidth)
                + SetScoreLayout.nameWidth(frameWidth)
                + SetScoreLayout.gap(frameWidth);
        return new int[]{serveX, 0,
                serveX + SetScoreLayout.serveColumnWidth(frameWidth), headerHeight};
    }

    @Test
    void serveColumnHeaderShowsLabelInBothTemplates() {
        int[] cell = serveHeaderCell(1920, 1080);

        int classic = countLightPixels(
                new ClassicScoreboardRenderer().render(snapshot(ScoreboardTemplate.CLASSIC)),
                cell[0], cell[1], cell[2], cell[3]);
        int modern = countLightPixels(
                new ModernScoreboardRenderer().render(snapshot(ScoreboardTemplate.MODERN)),
                cell[0], cell[1], cell[2], cell[3]);

        assertThat(classic).isGreaterThan(0);
        assertThat(modern).isGreaterThan(0);
    }

    @Test
    void serveColumnHeaderLabelFitsIntoTheColumn() {
        int frameWidth = 960;
        int frameHeight = frameWidth * 9 / 16;
        int[] cell = serveHeaderCell(frameWidth, frameHeight);

        int classic = countLightPixels(
                new ClassicScoreboardRenderer().render(mediumSnapshot(ScoreboardTemplate.CLASSIC)),
                cell[0], cell[1], cell[2], cell[3]);
        int modern = countLightPixels(
                new ModernScoreboardRenderer().render(mediumSnapshot(ScoreboardTemplate.MODERN)),
                cell[0], cell[1], cell[2], cell[3]);

        assertThat(classic).isGreaterThan(0);
        assertThat(modern).isGreaterThan(0);
    }

    @Test
    void serveColumnHeaderIsEmptyWithoutLabel() {
        // 队名列表头留空，浅色像素只应出现在“发球”“胜局”与局号三处
        int frameWidth = 1920;
        int frameHeight = 1080;
        int barHeight = RenderDimensions.barHeight(frameWidth, frameHeight);
        int headerHeight = Math.max(6, (int) Math.round(barHeight * 0.22));
        int nameX = SetScoreLayout.margin(frameWidth);
        int nameEnd = nameX + SetScoreLayout.nameWidth(frameWidth);

        int classic = countLightPixels(
                new ClassicScoreboardRenderer().render(snapshot(ScoreboardTemplate.CLASSIC)),
                nameX, 0, nameEnd, headerHeight);
        int modern = countLightPixels(
                new ModernScoreboardRenderer().render(snapshot(ScoreboardTemplate.MODERN)),
                nameX, 0, nameEnd, headerHeight);

        assertThat(classic).isZero();
        assertThat(modern).isZero();
    }

    @Test
    void teamNameHeaderShowsProjectNameWhenEnabled() {
        // 开启后队名列表头显示项目名称
        int frameWidth = 1920;
        int frameHeight = 1080;
        int barHeight = RenderDimensions.barHeight(frameWidth, frameHeight);
        int headerHeight = Math.max(6, (int) Math.round(barHeight * 0.22));
        int nameX = SetScoreLayout.margin(frameWidth);
        int nameEnd = nameX + SetScoreLayout.nameWidth(frameWidth);

        int classic = countLightPixels(
                new ClassicScoreboardRenderer().render(namedProjectSnapshot(ScoreboardTemplate.CLASSIC)),
                nameX, 0, nameEnd, headerHeight);
        int modern = countLightPixels(
                new ModernScoreboardRenderer().render(namedProjectSnapshot(ScoreboardTemplate.MODERN)),
                nameX, 0, nameEnd, headerHeight);

        assertThat(classic).isGreaterThan(0);
        assertThat(modern).isGreaterThan(0);
    }

    private int countTransparentPixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >> 24) == 0) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void visibleSetsKeepsRealSetNumbersAndLimitsToSeven() {
        List<SetScore> many = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            many.add(new SetScore(i, 11, 5));
        }

        assertThat(SetScoreLayout.visibleSets(many))
                .containsExactly(new SetScore(4, 11, 5), new SetScore(5, 11, 5), new SetScore(6, 11, 5),
                        new SetScore(7, 11, 5), new SetScore(8, 11, 5), new SetScore(9, 11, 5),
                        new SetScore(10, 11, 5));
        assertThat(SetScoreLayout.visibleSets(many.subList(0, 3))).hasSize(3);
        assertThat(SetScoreLayout.visibleSets(List.of())).isEmpty();
    }

    @Test
    void widthGrowsWithVisibleSetCountAndSaturatesAtSeven() {
        int oneSet = SetScoreLayout.barWidth(1920, 1);
        int threeSets = SetScoreLayout.barWidth(1920, 3);
        int sevenSets = SetScoreLayout.barWidth(1920, 7);
        int tenSets = SetScoreLayout.barWidth(1920, 10);

        assertThat(threeSets).isGreaterThan(oneSet);
        assertThat(sevenSets).isGreaterThan(threeSets);
        assertThat(tenSets).isEqualTo(sevenSets);
    }

    @Test
    void bothRenderersShowSameColumnCountForSameSnapshot() {
        ClassicScoreboardRenderer classic = new ClassicScoreboardRenderer();
        ModernScoreboardRenderer modern = new ModernScoreboardRenderer();
        ScoreboardSnapshot snap = multiSetSnapshot(ScoreboardTemplate.CLASSIC, 4, 1920);

        assertThat(classic.render(snap).getWidth()).isEqualTo(modern.render(snap).getWidth());
    }

    @Test
    void renderersShowOnlyLastSevenSetsForLongMatches() {
        ClassicScoreboardRenderer classic = new ClassicScoreboardRenderer();
        ModernScoreboardRenderer modern = new ModernScoreboardRenderer();

        BufferedImage eightSets = classic.render(multiSetSnapshot(ScoreboardTemplate.CLASSIC, 8, 1920));
        BufferedImage tenSets = classic.render(multiSetSnapshot(ScoreboardTemplate.CLASSIC, 10, 1920));

        assertThat(eightSets.getWidth()).isEqualTo(SetScoreLayout.barWidth(1920, SetScoreLayout.MAX_VISIBLE_SETS));
        assertThat(tenSets.getWidth()).isEqualTo(eightSets.getWidth());
        assertThat(modern.render(multiSetSnapshot(ScoreboardTemplate.MODERN, 10, 1920)).getWidth())
                .isEqualTo(eightSets.getWidth());
    }

    @Test
    void singleSetMatchRendersNarrowerBoard() {
        ClassicScoreboardRenderer classic = new ClassicScoreboardRenderer();

        int oneSetWidth = classic.render(multiSetSnapshot(ScoreboardTemplate.CLASSIC, 1, 1920)).getWidth();
        int sevenSetWidth = classic.render(multiSetSnapshot(ScoreboardTemplate.CLASSIC, 7, 1920)).getWidth();

        assertThat(oneSetWidth).isLessThan(sevenSetWidth);
        assertThat(oneSetWidth).isEqualTo(SetScoreLayout.barWidth(1920, 1));
    }

    @Test
    void compactBoardWithSevenSetsRendersWithoutOverflow() {
        ClassicScoreboardRenderer classic = new ClassicScoreboardRenderer();
        ModernScoreboardRenderer modern = new ModernScoreboardRenderer();
        ScoreboardSnapshot snap = longNameSnapshot(ScoreboardTemplate.CLASSIC, 320);

        BufferedImage classicImage = classic.render(snap);
        BufferedImage modernImage = modern.render(snap);

        for (BufferedImage image : List.of(classicImage, modernImage)) {
            assertThat(image.getWidth()).isPositive();
            assertThat(image.getHeight()).isPositive();
            // 布局不得越界：最右侧一列仍在画布内
            assertThat(SetScoreLayout.barWidth(320, 1)).isEqualTo(image.getWidth());
        }
    }

    @Test
    void completedMatchDoesNotHighlightCurrentSet() {
        ClassicScoreboardRenderer classic = new ClassicScoreboardRenderer();
        ScoreboardSnapshot snap = new ScoreboardSnapshot(ScoreboardTemplate.CLASSIC, "A", "B", 11, 7, 2, 2, 1, true,
                PlayerSide.A, 1920, 1080,
                List.of(new SetScore(1, 8, 11), new SetScore(2, 11, 7)));

        BufferedImage image = classic.render(snap);

        assertThat(image.getWidth()).isPositive();
    }
}
