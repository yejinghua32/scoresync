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
}
