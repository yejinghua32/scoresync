package com.scoresync.service.render;

import com.scoresync.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                ScoreboardTemplate.CLASSIC, com.scoresync.domain.PlayerSide.A, Instant.now(), Instant.now());
    }

    private VideoAsset video() {
        return new VideoAsset(1L, null, "C:/data/test.mp4", "测试视频", 1000L, 0L,
                VideoOriginType.PICKED, Instant.now(), Instant.now());
    }

    @Test
    void createsInitialAndPointSegmentsEndingAtVideoDuration() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false)
        ), new VideoProbeResult(1920, 1080, 5_000, "30000/1001", true), tempDir);

        assertThat(plan.segments()).extracting(RenderPlan.Segment::startTimeMs,
                RenderPlan.Segment::endTimeMs).containsExactly(
                tuple(0L, 1_000L), tuple(1_000L, 5_000L));
    }

    @Test
    void writesSegmentPngsIntoWorkDir() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false)
        ), new VideoProbeResult(1920, 1080, 5_000, "30000/1001", true), tempDir);

        assertThat(plan.segments()).allSatisfy(segment ->
                assertThat(segment.pngPath()).isNotNull().exists());
        assertThat(tempDir.resolve("segment-0000.png")).exists();
        assertThat(tempDir.resolve("segment-0001.png")).exists();
    }

    @Test
    void handlesSingleCueAsOneSegment() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false)
        ), new VideoProbeResult(1920, 1080, 5_000, "30000/1001", true), tempDir);

        assertThat(plan.segments()).extracting(RenderPlan.Segment::startTimeMs,
                RenderPlan.Segment::endTimeMs).containsExactly(tuple(0L, 5_000L));
    }

    @Test
    void freezesTemplateFromProject() {
        MatchProject modernProject = new MatchProject(2L, "现代比赛", 1L, 5000L, "甲", "乙", 3,
                ScoreboardTemplate.MODERN, com.scoresync.domain.PlayerSide.A, Instant.now(), Instant.now());

        RenderPlan plan = service.create(modernProject, video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false)
        ), new VideoProbeResult(1920, 1080, 5_000, "30000/1001", true), tempDir);

        assertThat(plan.template()).isEqualTo(ScoreboardTemplate.MODERN);
    }

    @Test
    void multipleCuesProduceMultipleSegments() {
        RenderPlan plan = service.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false),
                new ScoreboardCue(2_000, 2, 1, 1, 1, 0, 0, false)
        ), new VideoProbeResult(1920, 1080, 5_000, "30000/1001", true), tempDir);

        assertThat(plan.segments()).extracting(RenderPlan.Segment::startTimeMs,
                RenderPlan.Segment::endTimeMs).containsExactly(
                tuple(0L, 1_000L), tuple(1_000L, 2_000L), tuple(2_000L, 5_000L));
    }
}
