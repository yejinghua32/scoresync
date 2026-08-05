package com.scoresync.service;

import com.scoresync.domain.*;
import com.scoresync.support.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoreReplayServiceTest {

    private ScoreReplayService service = new ScoreReplayService();

    private ReplayResult replay(int targetWins, List<ScoreEvent> events) {
        return service.replay(targetWins, events);
    }

    private ScoreEvent event(long id, long timeMs, PlayerSide side, long sequenceNo) {
        return new ScoreEvent(id, 1, timeMs, side, sequenceNo, java.time.Instant.now(), java.time.Instant.now());
    }

    private List<ScoreEvent> points(Object... args) {
        java.util.List<ScoreEvent> result = new java.util.ArrayList<>();
        long id = 1;
        for (int i = 0; i < args.length; i += 2) {
            PlayerSide side = (PlayerSide) args[i];
            int count = (Integer) args[i + 1];
            for (int j = 0; j < count; j++) {
                long seq = id++;
                result.add(event(seq, result.size(), side, seq));
            }
        }
        return result;
    }

    private List<ScoreEvent> concat(List<ScoreEvent>... groups) {
        return IntStream.range(0, groups.length).boxed().flatMap(index -> groups[index].stream()
                .map(e -> event(e.id(), e.videoTimeMs() + index * 1_000, e.playerSide(), e.sequenceNo() + index * 100))).toList();
    }

    @Test
    void completesSetAtElevenToZero() {
        var result = replay(2, points(PlayerSide.A, 11));
        assertThat(result.completedSets()).containsExactly(new SetSummary(1, 11, 0, PlayerSide.A));
        assertThat(result.currentSetNo()).isEqualTo(2);
    }

    @Test
    void doesNotFinishAtElevenToTenButFinishesAtTwelveToTen() {
        var result = replay(2, points(PlayerSide.A, 10, PlayerSide.B, 10, PlayerSide.A, 2));
        assertThat(result.completedSets()).containsExactly(new SetSummary(1, 12, 10, PlayerSide.A));
    }

    @Test
    void movesToNextSetAndCompletesBestOfThree() {
        var result = replay(2, concat(points(PlayerSide.A, 11), points(PlayerSide.B, 11), points(PlayerSide.A, 11)));
        assertThat(result.matchCompleted()).isTrue();
        assertThat(result.setWinsA()).isEqualTo(2);
        assertThat(result.setWinsB()).isEqualTo(1);
    }

    @Test
    void rejectsEventAfterMatchCompletion() {
        assertThatThrownBy(() -> replay(1, points(PlayerSide.A, 12, PlayerSide.B, 10, PlayerSide.B, 1)))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("比赛已经结束");
    }

    @Test
    void preservesSequenceOrderForSameMillisecond() {
        var first = event(1, 1_000, PlayerSide.B, 1);
        var second = event(2, 1_000, PlayerSide.A, 2);
        assertThat(replay(2, List.of(second, first)).events())
                .extracting(DerivedScoreEvent::eventId).containsExactly(1L, 2L);
    }

    @Test
    void createsInitialCueAndAdvancesCueAtEachPoint() {
        ReplayResult result = replay(2, List.of(
                event(1, 1_000, PlayerSide.A, 1),
                event(2, 2_000, PlayerSide.B, 2)
        ));

        assertThat(result.scoreboardCues()).containsExactly(
                new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false),
                new ScoreboardCue(1_000, 1, 1, 1, 0, 0, 0, false),
                new ScoreboardCue(2_000, 2, 1, 1, 1, 0, 0, false)
        );
    }

    @Test
    void startsNextSetAtZeroZeroAfterSetWinningPoint() {
        ReplayResult result = replay(2, points(PlayerSide.A, 11));

        assertThat(result.scoreboardCues().get(result.scoreboardCues().size() - 1))
                .isEqualTo(new ScoreboardCue(10, 11, 2, 0, 0, 1, 0, false));
    }

    @Test
    void addsCueWithMatchCompletedTrueWhenMatchEnds() {
        ReplayResult result = replay(2, concat(points(PlayerSide.A, 11), points(PlayerSide.B, 11), points(PlayerSide.A, 11)));

        List<ScoreboardCue> cuesWithMatchCompleted = result.scoreboardCues().stream()
                .filter(ScoreboardCue::matchCompleted)
                .toList();

        assertThat(cuesWithMatchCompleted).hasSize(1);
        ScoreboardCue finalCue = cuesWithMatchCompleted.get(0);
        assertThat(finalCue.matchCompleted()).isTrue();
        assertThat(finalCue.setNumber()).isEqualTo(3);
        assertThat(finalCue.setWinsA()).isEqualTo(2);
        assertThat(finalCue.setWinsB()).isEqualTo(1);
        assertThat(finalCue.scoreA()).isEqualTo(11);
        assertThat(finalCue.scoreB()).isEqualTo(0);
    }
}
