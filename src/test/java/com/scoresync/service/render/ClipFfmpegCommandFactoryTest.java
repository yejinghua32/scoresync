package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.ClipExportPlan;
import com.scoresync.domain.ClipFrameIndex;
import com.scoresync.domain.ClipSegment;
import com.scoresync.domain.ClipSegmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClipFfmpegCommandFactoryTest {

    @TempDir
    Path tempDir;

    private ClipFfmpegCommandFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ClipFfmpegCommandFactory(new AppProperties());
    }

    @Test
    void selectsFramesByTimestampWindowsFromFrameIndex() {
        ClipFrameIndex index = index();
        List<ClipSegment> segments = List.of(segment(10, 20), segment(40, 50));

        String selection = factory.timeSelection(index, segments);

        // 半开时间区间 [起点帧时间, 终点帧时间)，不按平均帧率换算
        assertThat(selection)
                .isEqualTo("gte(t\\,0.333333)*lt(t\\,0.666667)+gte(t\\,1.333333)*lt(t\\,1.666667)");
    }

    @Test
    void windowsShrinkOverlappingRangesToStayDisjoint() {
        ClipFrameIndex index = index();
        List<ClipSegment> segments = List.of(segment(10, 20), segment(19, 30), segment(50, 60));

        List<long[]> windows = factory.windows(index, segments);

        // 第二段起点过早地落在第一段时间内：收缩到第一段的终点
        assertThat(windows).hasSize(3);
        assertThat(windows.get(0)).containsExactly(333_333L, 666_667L);
        assertThat(windows.get(1)).containsExactly(666_667L, 1_000_000L);
        assertThat(windows.get(2)).containsExactly(1_666_667L, 1_966_667L);
    }

    @Test
    void setptsRebasesSegmentsIntoContinuousTimeline() {
        ClipFrameIndex index = index();
        List<ClipSegment> segments = List.of(segment(10, 20), segment(40, 50));

        String setpts = factory.piecewiseSetpts(index, segments);

        // 第一段整体前移到零点
        assertThat(setpts).startsWith("PTS+(-0.333333)/TB");
        // 后续段按落入的时间区间额外前移，与个别帧的到达顺序无关（B 帧安全）
        assertThat(setpts).contains("+(-0.666666)/TB*gte(T\\,1.333333)");
    }

    @Test
    void singleSegmentNeedsNoPiecewiseCorrection() {
        ClipFrameIndex index = index();
        List<ClipSegment> segments = List.of(segment(10, 20));

        String setpts = factory.piecewiseSetpts(index, segments);

        assertThat(setpts).isEqualTo("PTS+(-0.333333)/TB");
        assertThat(setpts).doesNotContain("gte(");
    }

    @Test
    void videoAndAudioUseTheSameSelectionAndTimeline() throws Exception {
        ClipFrameIndex index = index();
        List<ClipSegment> segments = List.of(segment(10, 20), segment(40, 50));
        ClipExportPlan plan = new ClipExportPlan("/videos/source.mp4", segments, index, true);

        FfmpegCommand command = factory.create(plan, tempDir.resolve("work"), tempDir.resolve("out.mp4"));
        String script = Files.readString(command.filterScript());

        int videoSelect = script.indexOf("select='");
        int audioSelect = script.indexOf("aselect='");
        String videoExpr = script.substring(videoSelect + "select='".length(),
                script.indexOf("',setpts", videoSelect));
        String audioExpr = script.substring(audioSelect + "aselect='".length(),
                script.indexOf("',asetpts", audioSelect));
        assertThat(videoExpr).isEqualTo(audioExpr);

        int videoSetpts = script.indexOf("setpts='");
        int audioSetpts = script.indexOf("asetpts='");
        String videoTimeline = script.substring(videoSetpts + "setpts='".length(),
                script.indexOf("'[vout]", videoSetpts));
        String audioTimeline = script.substring(audioSetpts + "asetpts='".length(),
                script.indexOf("'[aout]", audioSetpts));
        assertThat(videoTimeline).isEqualTo(audioTimeline);
    }

    @Test
    void commandUsesFilterScriptAndReencodesVideo() throws Exception {
        ClipFrameIndex index = index();
        List<ClipSegment> segments = List.of(segment(10, 20), segment(40, 50));
        ClipExportPlan plan = new ClipExportPlan("/videos/source.mp4", segments, index, true);

        Path workDir = tempDir.resolve("work");
        Path output = tempDir.resolve("out.mp4");
        FfmpegCommand command = factory.create(plan, workDir, output);

        List<String> args = command.arguments();
        assertThat(args).contains("-filter_complex_script");
        assertThat(args).contains("-c:v", "libx264");
        // 不允许用关键帧 copy
        assertThat(args).doesNotContain("-c", "copy");
        assertThat(args).contains("-map", "[vout]", "-map", "[aout]");

        String script = Files.readString(command.filterScript());
        assertThat(script).contains("[0:v]select='gte(t\\,0.333333)*lt(t\\,0.666667)+gte(t\\,1.333333)*lt(t\\,1.666667)'");
        assertThat(script).contains("setpts='PTS+(-0.333333)/TB+(-0.666666)/TB*gte(T\\,1.333333)'");
        assertThat(script).contains("[0:a]aselect=");
    }

    @Test
    void commandOmitsAudioWhenSourceHasNoAudioTrack() throws Exception {
        ClipFrameIndex index = index();
        ClipExportPlan plan = new ClipExportPlan("/videos/source.mp4",
                List.of(segment(10, 20)), index, false);

        Path workDir = tempDir.resolve("work");
        FfmpegCommand command = factory.create(plan, workDir, tempDir.resolve("out.mp4"));

        assertThat(command.arguments()).contains("-an");
        assertThat(command.arguments()).doesNotContain("[aout]");

        String script = Files.readString(command.filterScript());
        assertThat(script).doesNotContain("[0:a]");
    }

    @Test
    void planReportsTotalDurationOfKeptSegments() {
        ClipFrameIndex index = index();
        ClipExportPlan plan = new ClipExportPlan("/videos/source.mp4",
                List.of(segment(10, 20), segment(40, 50)), index, true);

        // 两段各 10 帧，时间戳按 30fps 逐帧取整
        assertThat(plan.totalDurationUs()).isEqualTo(666_668L);
    }

    private ClipSegment segment(int startFrame, int endFrame) {
        return new ClipSegment(0L, 0L, ClipSegmentType.KEEP, startFrame, endFrame,
                false, Instant.now(), Instant.now());
    }

    /**
     * 30fps 的固定帧率索引
     */
    private ClipFrameIndex index() {
        long[] pts = new long[60];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = Math.round(i * 1_000_000d / 30d);
        }
        return new ClipFrameIndex(pts, new long[0], 0L);
    }
}
