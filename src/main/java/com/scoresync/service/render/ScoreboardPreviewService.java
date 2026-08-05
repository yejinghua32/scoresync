package com.scoresync.service.render;

import com.scoresync.domain.ScoreboardSnapshot;
import com.scoresync.domain.ScoreboardTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计分板预览服务
 * 生成计分板的PNG预览图片，用于前端展示
 */
@Service
public class ScoreboardPreviewService {

    private static final int MIN_WIDTH = 320;
    private static final int MAX_WIDTH = 1920;

    private final Map<ScoreboardTemplate, ScoreboardTemplateRenderer> rendererMap;

    public ScoreboardPreviewService(List<ScoreboardTemplateRenderer> renderers) {
        this.rendererMap = renderers.stream()
                .collect(Collectors.toMap(ScoreboardTemplateRenderer::template, Function.identity()));
    }

    /**
     * 生成计分板预览图片
     *
     * @param snapshot 计分板快照数据
     * @return PNG格式的预览图片字节数组
     */
    public byte[] generatePreview(ScoreboardSnapshot snapshot) {
        int renderWidth = snapshot.frameWidth();
        if (renderWidth <= 0) {
            renderWidth = MIN_WIDTH;
        }
        renderWidth = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, renderWidth));
        int renderHeight = renderWidth * 9 / 16;

        ScoreboardSnapshot scaledSnapshot = new ScoreboardSnapshot(
                snapshot.template(),
                snapshot.playerA(),
                snapshot.playerB(),
                snapshot.scoreA(),
                snapshot.scoreB(),
                snapshot.setNumber(),
                snapshot.setWinsA(),
                snapshot.setWinsB(),
                snapshot.matchCompleted(),
                snapshot.firstServer(),
                renderWidth,
                renderHeight
        );

        ScoreboardTemplateRenderer renderer = rendererMap.get(snapshot.template());
        if (renderer == null) {
            throw new IllegalArgumentException("Unsupported template: " + snapshot.template());
        }

        BufferedImage image = renderer.render(scaledSnapshot);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate preview", e);
        }
    }
}
