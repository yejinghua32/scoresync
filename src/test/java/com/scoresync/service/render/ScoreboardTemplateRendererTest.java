package com.scoresync.service.render;

import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreboardSnapshot;
import com.scoresync.domain.ScoreboardTemplate;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScoreboardTemplateRendererTest {

    private ScoreboardSnapshot snapshot(ScoreboardTemplate template) {
        return new ScoreboardSnapshot(template, "Player A", "Player B", 5, 3, 2, 1, 0, false, PlayerSide.A, 1920, 1080);
    }

    private ScoreboardSnapshot compactSnapshot(ScoreboardTemplate template) {
        return new ScoreboardSnapshot(template, "Player A", "Player B", 5, 3, 2, 1, 0, false, PlayerSide.A, 320, 180);
    }

    @Test
    void classicRendererProducesPngWithTransparency() throws Exception {
        ClassicScoreboardRenderer renderer = new ClassicScoreboardRenderer();
        BufferedImage image = renderer.render(snapshot(ScoreboardTemplate.CLASSIC));

        assertThat(image.getType()).isEqualTo(BufferedImage.TYPE_INT_ARGB);
        assertThat(image.getWidth()).isEqualTo(RenderDimensions.barWidth(1920));
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

        assertThat(compactClassic.getWidth()).isEqualTo(RenderDimensions.barWidth(320));
        assertThat(compactClassic.getHeight()).isLessThan(classicImage.getHeight());
        assertThat((double) compactClassic.getWidth() / classicImage.getWidth())
                .isEqualTo(320.0 / 1920.0, within(0.0001));
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
}
