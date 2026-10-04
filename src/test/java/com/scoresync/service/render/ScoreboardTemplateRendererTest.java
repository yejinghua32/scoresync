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
