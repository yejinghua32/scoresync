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
                .isEqualTo(new ScoreboardCue(10, 11, 2, 0, 0, 1, 0, false,
                        List.of(new SetScore(1, 11, 0), new SetScore(2, 0, 0))));
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

    @Test
    void initialCueStartsWithFirstSetAtZeroZero() {
        ReplayResult result = replay(4, List.of());

        assertThat(result.scoreboardCues().get(0).setScores())
                .containsExactly(new SetScore(1, 0, 0));
    }

    @Test
    void cueAfterSetWinningPointKeepsFinalScoreAndAppendsFreshSet() {
        ReplayResult result = replay(4, points(PlayerSide.A, 11));

        List<ScoreboardCue> cues = result.scoreboardCues();
        // 局末比分：已结束的局保留最终分
        assertThat(cues.get(cues.size() - 2).setScores())
                .containsExactly(new SetScore(1, 11, 0));
        // 局胜点后生成的最后一个 cue：追加下一局 0-0
        assertThat(cues.get(cues.size() - 1).setScores())
                .containsExactly(new SetScore(1, 11, 0), new SetScore(2, 0, 0));
    }

    @Test
    void finalCueWhenMatchEndsHasNoTrailingEmptySet() {
        ReplayResult result = replay(2, concat(points(PlayerSide.A, 11), points(PlayerSide.B, 11), points(PlayerSide.A, 11)));

        ScoreboardCue finalCue = result.scoreboardCues().get(result.scoreboardCues().size() - 1);
        assertThat(finalCue.setScores()).containsExactly(
                new SetScore(1, 11, 0),
                new SetScore(2, 0, 11),
                new SetScore(3, 11, 0));
    }

    @Test
    void earlyCueSetScoresAreNotMutatedByLaterPoints() {
        ReplayResult result = replay(2, points(PlayerSide.A, 12));

        ScoreboardCue firstPointCue = result.scoreboardCues().get(1);
        assertThat(firstPointCue.setScores()).containsExactly(new SetScore(1, 1, 0));
        assertThat(result.scoreboardCues().get(result.scoreboardCues().size() - 1).setScores())
                .containsExactly(new SetScore(1, 11, 0), new SetScore(2, 1, 0));
    }

    @Test
    void keepsFullSetHistoryBeyondSevenSets() {
        // 自定义赛制：5 胜制，双方交替各胜 4 局后第 9 局正在进行
        List<ScoreEvent> events = new java.util.ArrayList<>();
        long id = 1;
        long time = 0;
        for (int set = 1; set <= 8; set++) {
            PlayerSide winner = set % 2 == 1 ? PlayerSide.A : PlayerSide.B;
            for (int point = 0; point < 11; point++) {
                events.add(event(id, time += 100, winner, id));
                id++;
            }
        }

        ReplayResult result = replay(5, events);

        ScoreboardCue lastCue = result.scoreboardCues().get(result.scoreboardCues().size() - 1);
        assertThat(result.matchCompleted()).isFalse();
        assertThat(lastCue.setNumber()).isEqualTo(9);
        assertThat(lastCue.setScores()).hasSize(9);
        assertThat(lastCue.setScores().get(0)).isEqualTo(new SetScore(1, 11, 0));
        assertThat(lastCue.setScores().get(7)).isEqualTo(new SetScore(8, 0, 11));
        assertThat(lastCue.setScores().get(8)).isEqualTo(new SetScore(9, 0, 0));
    }
}
