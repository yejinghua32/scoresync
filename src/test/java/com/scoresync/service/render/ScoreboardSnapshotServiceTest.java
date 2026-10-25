package com.scoresync.service.render;

import com.scoresync.domain.MatchProject;
import com.scoresync.domain.ScoreboardCue;
import com.scoresync.domain.ScoreboardSnapshot;
import com.scoresync.domain.ScoreboardTemplate;
import com.scoresync.domain.SetScore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreboardSnapshotServiceTest {

    private MatchProject project(ScoreboardTemplate template) {
        return new MatchProject(1L, "Test Match", 1L, 5000L, "Player A", "Player B", 3, template, com.scoresync.domain.PlayerSide.A, Instant.now(), Instant.now());
    }

    @Test
    void returnsLastCueAtOrBeforeRequestedTime() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false),
                new ScoreboardCue(2_000, 2, 1, 1, 1, 0, 0, false)
        );

        ScoreboardSnapshot snapshot = service.at(proj, cues, 1_500, 1920, 1080);

        assertThat(snapshot.scoreA()).isEqualTo(1);
        assertThat(snapshot.scoreB()).isZero();
        assertThat(snapshot.template()).isEqualTo(ScoreboardTemplate.CLASSIC);
    }

    @Test
    void handlesNegativeTimeAsZero() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false)
        );

        ScoreboardSnapshot snapshot = service.at(proj, cues, -500, 1920, 1080);

        assertThat(snapshot.scoreA()).isZero();
        assertThat(snapshot.scoreB()).isZero();
    }

    @Test
    void usesLastCueWhenTimeExceedsVideoDuration() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.MODERN);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false)
        );

        ScoreboardSnapshot snapshot = service.at(proj, cues, 10_000, 1920, 1080);

        assertThat(snapshot.scoreA()).isEqualTo(1);
        assertThat(snapshot.scoreB()).isZero();
        assertThat(snapshot.template()).isEqualTo(ScoreboardTemplate.MODERN);
    }

    @Test
    void preservesAllFieldsFromSelectedCue() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 5, 3, 0, 0, false),
                new ScoreboardCue(2_000, 2, 2, 5, 3, 1, 0, false)
        );

        ScoreboardSnapshot snapshot = service.at(proj, cues, 2_000, 1920, 1080);

        assertThat(snapshot.scoreA()).isEqualTo(5);
        assertThat(snapshot.scoreB()).isEqualTo(3);
        assertThat(snapshot.setNumber()).isEqualTo(2);
        assertThat(snapshot.setWinsA()).isEqualTo(1);
        assertThat(snapshot.setWinsB()).isZero();
        assertThat(snapshot.matchCompleted()).isFalse();
    }

    @Test
    void handlesExactCueTimeMatch() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false)
        );

        ScoreboardSnapshot snapshot = service.at(proj, cues, 1_000, 1920, 1080);

        assertThat(snapshot.scoreA()).isEqualTo(1);
    }

    @Test
    void picksLastCueWhenTimeAndSequenceAreIdentical() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 11, 0, 0, 0, false,
                        List.of(new SetScore(1, 11, 0))),
                new ScoreboardCue(1_000, 1, 2, 0, 0, 1, 0, false,
                        List.of(new SetScore(1, 11, 0), new SetScore(2, 0, 0)))
        );

        ScoreboardSnapshot snapshot = service.at(proj, cues, 1_000, 1920, 1080);

        assertThat(snapshot.setNumber()).isEqualTo(2);
        assertThat(snapshot.setWinsA()).isEqualTo(1);
        assertThat(snapshot.setScores()).containsExactly(
                new SetScore(1, 11, 0), new SetScore(2, 0, 0));
    }

    @Test
    void snapshotCarriesSetScoresFromSelectedCue() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.MODERN);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false,
                        List.of(new SetScore(1, 0, 0))),
                new ScoreboardCue(5_000, 9, 3, 5, 2, 1, 1, false,
                        List.of(new SetScore(1, 11, 0),
                                new SetScore(2, 8, 11),
                                new SetScore(3, 5, 2)))
        );

        ScoreboardSnapshot snapshot = service.at(proj, cues, 5_000, 1920, 1080);

        assertThat(snapshot.setScores()).containsExactly(
                new SetScore(1, 11, 0),
                new SetScore(2, 8, 11),
                new SetScore(3, 5, 2));
    }

    @Test
    void highlightsScoringSideAtTheScoreMoment() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false, List.of(new SetScore(1, 1, 0)),
                        com.scoresync.domain.PlayerSide.A)
        );

        assertThat(service.at(proj, cues, 1_000, 1920, 1080).highlightedSide())
                .isEqualTo(com.scoresync.domain.PlayerSide.A);
    }

    @Test
    void keepsHighlightUntilTheNextScoreChange() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false, List.of(new SetScore(1, 1, 0)),
                        com.scoresync.domain.PlayerSide.A),
                new ScoreboardCue(2_000, 2, 1, 1, 1, 0, 0, false, List.of(new SetScore(1, 1, 1)),
                        com.scoresync.domain.PlayerSide.B)
        );

        // 距离上次得分已久，高亮仍然保持，直到下一次比分变化
        assertThat(service.at(proj, cues, 1_999, 1920, 1080).highlightedSide())
                .isEqualTo(com.scoresync.domain.PlayerSide.A);
        // 下一次比分变化时切换到新的得分方
        assertThat(service.at(proj, cues, 2_000, 1920, 1080).highlightedSide())
                .isEqualTo(com.scoresync.domain.PlayerSide.B);
    }

    @Test
    void lastScoreKeepsHighlightUntilTheEndOfTheVideo() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false, List.of(new SetScore(1, 1, 0)),
                        com.scoresync.domain.PlayerSide.A)
        );

        assertThat(service.at(proj, cues, 600_000, 1920, 1080).highlightedSide())
                .isEqualTo(com.scoresync.domain.PlayerSide.A);
    }

    @Test
    void noHighlightBeforeTheFirstScore() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false, List.of(new SetScore(1, 1, 0)),
                        com.scoresync.domain.PlayerSide.A)
        );

        assertThat(service.at(proj, cues, 0, 1920, 1080).highlightedSide()).isNull();
    }

    @Test
    void laterScoreTakesOverTheHighlight() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false, List.of(new SetScore(1, 1, 0)),
                        com.scoresync.domain.PlayerSide.A),
                new ScoreboardCue(2_000, 2, 1, 1, 1, 0, 0, false, List.of(new SetScore(1, 1, 1)),
                        com.scoresync.domain.PlayerSide.B)
        );

        // 新得分发生在上一次高亮期间，由新得分方覆盖
        assertThat(service.at(proj, cues, 2_100, 1920, 1080).highlightedSide())
                .isEqualTo(com.scoresync.domain.PlayerSide.B);
        // 高亮一直保持到下一次比分变化
        assertThat(service.at(proj, cues, 3_499, 1920, 1080).highlightedSide())
                .isEqualTo(com.scoresync.domain.PlayerSide.B);
    }

    @Test
    void sameMillisecondScoresResolveToTheLastSequenceNumber() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false, List.of(new SetScore(1, 1, 0)),
                        com.scoresync.domain.PlayerSide.A),
                new ScoreboardCue(1_000, 2, 1, 1, 1, 0, 0, false, List.of(new SetScore(1, 1, 1)),
                        com.scoresync.domain.PlayerSide.B)
        );

        assertThat(service.at(proj, cues, 1_000, 1920, 1080).highlightedSide())
                .isEqualTo(com.scoresync.domain.PlayerSide.B);
    }

    @Test
    void setWinningPointStillHighlightsWinnerOnTheNewSetCue() {
        var service = new ScoreboardSnapshotService();
        var proj = project(ScoreboardTemplate.CLASSIC);
        var cues = List.of(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 11, 0, 0, 0, false, List.of(new SetScore(1, 11, 0)),
                        com.scoresync.domain.PlayerSide.A),
                new ScoreboardCue(1_000, 1, 2, 0, 0, 1, 0, false,
                        List.of(new SetScore(1, 11, 0), new SetScore(2, 0, 0)),
                        com.scoresync.domain.PlayerSide.A)
        );

        var snapshot = service.at(proj, cues, 1_000, 1920, 1080);

        assertThat(snapshot.setNumber()).isEqualTo(2);
        assertThat(snapshot.scoreA()).isZero();
        assertThat(snapshot.highlightedSide()).isEqualTo(com.scoresync.domain.PlayerSide.A);
    }
}
