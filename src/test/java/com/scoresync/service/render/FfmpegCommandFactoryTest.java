package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FfmpegCommandFactoryTest {

    private final AppProperties appProperties = new AppProperties();
    private final FfmpegCommandFactory factory = new FfmpegCommandFactory(appProperties);
    private final ScoreboardSnapshotService snapshotService = new ScoreboardSnapshotService();
    private final RenderPlanService planService = new RenderPlanService(
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

    private RenderPlan planWithSegments() throws Exception {
        VideoProbeResult probe = new VideoProbeResult(1920, 1080, 5_000, "30000/1001", true);
        RenderPlan plan = planService.create(project(), video(), List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false)
        ), probe, tempDir);
        return plan;
    }

    @Test
    void writesH264AacCommandWithLeftBottomOverlay() throws Exception {
        RenderPlan plan = planWithSegments();
        Path workDir = Files.createDirectories(tempDir.resolve("cmd"));

        FfmpegCommand command = factory.create(plan, workDir);

        assertThat(command.arguments()).contains("-c:v", "libx264", "-c:a", "aac", "-map", "0:a?");
        assertThat(command.arguments()).contains("-crf", "18", "-pix_fmt", "yuv420p",
                "-fps_mode", "passthrough", "-movflags", "+faststart", "-nostats");
        String filterContent = Files.readString(command.filterScript());
        assertThat(filterContent).contains("overlay=x=48:y=");
    }

    @Test
    void limitsOutputDurationToProbeDuration() throws Exception {
        RenderPlan plan = planWithSegments();
        Path workDir = Files.createDirectories(tempDir.resolve("cmd_dur"));

        FfmpegCommand command = factory.create(plan, workDir);

        List<String> args = command.arguments();
        int tIndex = args.indexOf("-t");
        assertThat(tIndex).isGreaterThan(-1);
        assertThat(args.get(tIndex + 1)).isEqualTo("5.000");
    }

    @Test
    void addsLoopInputForEachSegmentPng() throws Exception {
        RenderPlan plan = planWithSegments();
        Path workDir = Files.createDirectories(tempDir.resolve("cmd2"));

        FfmpegCommand command = factory.create(plan, workDir);

        List<String> args = command.arguments();
        long loopCount = args.stream().filter("-loop"::equals).count();
        assertThat(loopCount).isEqualTo(plan.segments().size());
        assertThat(args).contains("-framerate", "30000/1001");
    }

    @Test
    void filterScriptUsesLtForAllButLastSegment() throws Exception {
        RenderPlan plan = planWithSegments();
        Path workDir = Files.createDirectories(tempDir.resolve("cmd3"));

        FfmpegCommand command = factory.create(plan, workDir);
        String filterContent = Files.readString(command.filterScript());

        assertThat(filterContent).contains("enable='gte(t,0.000)*lt(t,1.000)'");
        assertThat(filterContent).contains("enable='gte(t,1.000)'");
        assertThat(filterContent).contains("[scoreboard]");
    }

    @Test
    void temporaryOutputResidesInWorkDir() throws Exception {
        RenderPlan plan = planWithSegments();
        Path workDir = Files.createDirectories(tempDir.resolve("cmd4"));

        FfmpegCommand command = factory.create(plan, workDir);

        assertThat(command.temporaryOutput().getParent()).isEqualTo(workDir);
        assertThat(command.temporaryOutput().toString()).endsWith(".mp4");
    }

    @Test
    void overlayYPlacesBarAboveBottomMargin() throws Exception {
        RenderPlan plan = planWithSegments();
        Path workDir = Files.createDirectories(tempDir.resolve("cmd5"));

        FfmpegCommand command = factory.create(plan, workDir);
        String filterContent = Files.readString(command.filterScript());

        int frameHeight = 1080;
        int overlayHeight = RenderDimensions.barHeight(1920, frameHeight);
        int expectedY = frameHeight - 48 - overlayHeight;
        assertThat(filterContent).contains("overlay=x=48:y=" + expectedY);
    }
}
