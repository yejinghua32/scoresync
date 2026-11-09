package com.scoresync.service.render;

import com.scoresync.config.AppProperties;
import com.scoresync.domain.RenderPlan;
import com.scoresync.domain.VideoProbeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FfmpegRenderServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsOutOfMemoryExitCodeToChineseMessage() {
        assertThat(FfmpegRenderService.describeExitCode(-12))
                .isEqualTo("FFmpeg 内存或系统资源不足（退出码 -12）");
        assertThat(FfmpegRenderService.describeExitCode(1)).contains("退出码 1");
    }

    @Test
    void taskTypeDistinguishesClipAndScoreCommands() {
        Path filter = tempDir.resolve("filter.txt");
        assertThat(FfmpegRenderService.taskType(new FfmpegCommand(
                List.of("ffmpeg"), filter, tempDir.resolve("output.mp4"))))
                .isEqualTo("SCORE_RENDER");
        assertThat(FfmpegRenderService.taskType(new FfmpegCommand(
                List.of("ffmpeg"), filter, tempDir.resolve("clip-output.mp4"))))
                .isEqualTo("CLIP_RENDER");
    }

    @Test
    void capturesStderrAndKeepsOnlyBoundedTailOnFailure() throws Exception {
        Path executable;
        List<String> arguments;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            executable = Path.of("C:\\Windows\\System32\\cmd.exe");
            assumeTrue(Files.isExecutable(executable));
            arguments = List.of(executable.toString(), "/d", "/c",
                    "(for /L %i in (1,1,35000) do @<nul set /p=0 1>&2)& <nul set /p=ERROR-TAIL 1>&2& exit /b 255");
        } else {
            executable = Path.of("/bin/sh");
            assumeTrue(Files.isExecutable(executable));
            arguments = List.of(executable.toString(), "-c",
                    "printf '%070000d' 0 >&2; printf 'ERROR-TAIL' >&2; exit 255");
        }
        AppProperties properties = new AppProperties();
        properties.getRender().setFfmpegPath(executable.toString());
        FfmpegRenderService service = new FfmpegRenderService(properties);

        Path workDir = Files.createDirectories(tempDir.resolve("work"));
        Path tempOutput = workDir.resolve("output.mp4");
        Path filter = workDir.resolve("filter.txt");
        FfmpegCommand command = new FfmpegCommand(arguments, filter, tempOutput);

        assertThatThrownBy(() -> service.execute(42L, workDir, 1000L, command,
                tempDir.resolve("final.mp4"), percent -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("退出码");

        String log = Files.readString(workDir.resolve("ffmpeg.log"));
        assertThat(log).contains("ERROR-TAIL");
        assertThat(log.length()).isGreaterThan(65_000);
    }
}
