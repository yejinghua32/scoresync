package com.scoresync.service.render;

import com.scoresync.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RenderPlanServiceTest {

    private final ScoreboardSnapshotService snapshotService = new ScoreboardSnapshotService();
    private final RenderPlanService service = new RenderPlanService(
            List.of(new ClassicScoreboardRenderer(), new ModernScoreboardRenderer()), snapshotService);

    @TempDir
    Path tempDir;

    private MatchProject project() {
        return new MatchProject(1L, "测试比赛", 1L, 5000L, "甲", "乙", 3,
                ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A, false, Instant.now(), Instant.now());
    }

    private VideoAsset video() {
        return new VideoAsset(1L, null, "C:/data/test.mp4", "测试视频", 1000L, 0L,
                VideoOriginType.PICKED, Instant.now(), Instant.now());
    }

    private VideoProbeResult probe() {
        return new VideoProbeResult(1920, 1080, 5_000, "30000/1001", true);
    }

    @Test
    void startsSegmentsAtFirstScoreTime() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false)
        ), probe(), tempDir);

        assertThat(plan.segments()).extracting(RenderPlan.Segment::startTimeMs,
                RenderPlan.Segment::endTimeMs).containsExactly(tuple(1_000L, 5_000L));
    }

    @Test
    void startsSegmentsAtVideoStartWhenFirstScoreIsAtZero() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(0, 1, 1, 1, 0, 0, 0, false)
        ), probe(), tempDir);

        assertThat(plan.segments()).extracting(RenderPlan.Segment::startTimeMs,
                RenderPlan.Segment::endTimeMs).containsExactly(tuple(0L, 5_000L));
    }

    @Test
    void createsNoSegmentsWithoutAnyScoreEvent() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false)
        ), probe(), tempDir);

        assertThat(plan.segments()).isEmpty();
    }

    @Test
    void createsNoSegmentsWhenFirstScoreReachesVideoEnd() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(5_000, 1, 1, 1, 0, 0, 0, false)
        ), probe(), tempDir);

        assertThat(plan.segments()).isEmpty();
    }

    @Test
    void createsNoSegmentsWhenFirstScoreIsBeyondProbeDuration() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(9_000, 1, 1, 1, 0, 0, 0, false)
        ), probe(), tempDir);

        assertThat(plan.segments()).isEmpty();
    }

    @Test
    void writesSegmentPngsIntoWorkDir() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false),
                new ScoreboardCue(2_000, 2, 1, 1, 1, 0, 0, false)
        ), probe(), tempDir);

        assertThat(plan.segments()).allSatisfy(segment ->
                assertThat(segment.pngPath()).isNotNull().exists());
        assertThat(tempDir.resolve("segment-0000.png")).exists();
        assertThat(tempDir.resolve("segment-0001.png")).exists();
    }

    @Test
    void writesNoPngWhenThereIsNoScoreEvent() {
        service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false)
        ), probe(), tempDir);

        assertThat(tempDir.resolve("segment-0000.png")).doesNotExist();
    }

    @Test
    void freezesTemplateFromProject() {
        MatchProject modernProject = new MatchProject(2L, "现代比赛", 1L, 5000L, "甲", "乙", 3,
                ScoreboardTemplate.MODERN, com.scoresync.domain.PlayerSide.A, false, Instant.now(), Instant.now());

        RenderPlan plan = service.create(modernProject, video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false)
        ), probe(), tempDir);

        assertThat(plan.template()).isEqualTo(ScoreboardTemplate.MODERN);
    }

    private ScoreboardCue scored(long timeMs, long seq, int a, int b, PlayerSide side) {
        return new ScoreboardCue(timeMs, seq, 1, a, b, 0, 0, false,
                List.of(new SetScore(1, a, b)), side);
    }

    private int[] highlightRows(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        java.awt.Color target = SetScoreLayout.HIGHLIGHT_COLOR;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                java.awt.Color p = new java.awt.Color(image.getRGB(x, y), true);
                if (Math.abs(p.getRed() - target.getRed()) <= 24
                        && Math.abs(p.getGreen() - target.getGreen()) <= 24
                        && Math.abs(p.getBlue() - target.getBlue()) <= 24) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (minY > maxY) {
            return null;
        }
        return new int[]{minY, maxY};
    }

    @Test
    void segmentPngsCarryTheHighlightOfTheScoringSide() throws Exception {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                scored(1_000, 1, 1, 0, PlayerSide.A),
                scored(2_000, 2, 1, 1, PlayerSide.B),
                scored(3_000, 3, 2, 1, PlayerSide.A)
        ), probe(), tempDir);

        int[] sideA = highlightRows(plan.segments().get(0).pngPath());
        int[] sideB = highlightRows(plan.segments().get(1).pngPath());
        int[] sideAAgain = highlightRows(plan.segments().get(2).pngPath());

        assertThat(sideA).isNotNull();
        assertThat(sideB).isNotNull();
        assertThat(sideAAgain).isNotNull();
        // A 队行在上、B 队行在下，高亮位置随得分方切换
        assertThat(sideA[1]).isLessThan(sideB[0]);
        // 再次由 A 方得分时，高亮回到与第一次一致的 A 行位置
        assertThat(sideAAgain[0]).isEqualTo(sideA[0]);
        assertThat(sideAAgain[1]).isEqualTo(sideA[1]);
    }

    @Test
    void highlightLastsUntilTheNextScoreChange() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                scored(1_000, 1, 1, 0, PlayerSide.A),
                scored(4_000, 2, 1, 1, PlayerSide.B)
        ), probe(), tempDir);

        // 分段边界落在得分时刻，高亮覆盖整段直到下一次比分变化
        assertThat(plan.segments()).extracting(RenderPlan.Segment::startTimeMs,
                RenderPlan.Segment::endTimeMs).containsExactly(
                tuple(1_000L, 4_000L), tuple(4_000L, 5_000L));
    }

    @Test
    void multipleCuesProduceMultipleSegmentsAfterFirstScore() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false),
                new ScoreboardCue(2_000, 2, 1, 1, 1, 0, 0, false)
        ), probe(), tempDir);

        assertThat(plan.segments()).extracting(RenderPlan.Segment::startTimeMs,
                RenderPlan.Segment::endTimeMs).containsExactly(
                tuple(1_000L, 2_000L), tuple(2_000L, 5_000L));
    }
}
