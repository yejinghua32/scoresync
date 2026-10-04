package com.scoresync.service;

import com.scoresync.domain.*;
import com.scoresync.support.DomainValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 比分回放服务
 * 根据得分事件序列重放比赛过程，实时计算局比分、总局数、当前发球方等派生状态
 * 同时作为校验器，向已结束比赛添加新得分时会抛出异常
 */
@Service
public class ScoreReplayService {

    /**
     * 重放比赛过程，计算派生比分状态
     *
     * @param targetWins 获胜所需总局数
     * @param events     得分事件列表（按时间排序）
     * @return 包含已完成局数、派生事件、当前比分、比分提示等完整回放结果
     */
    public ReplayResult replay(int targetWins, List<ScoreEvent> events) {
        if (targetWins < 1) {
            throw new DomainValidationException("targetWins must be at least 1");
        }

        List<ScoreEvent> sorted = events.stream()
                .sorted(Comparator.comparingLong(ScoreEvent::videoTimeMs)
                        .thenComparingLong(ScoreEvent::sequenceNo))
                .toList();

        List<SetSummary> completedSets = new ArrayList<>();
        List<DerivedScoreEvent> derivedEvents = new ArrayList<>();
        List<ScoreboardCue> scoreboardCues = new ArrayList<>();

        // 截至当前时刻已开始的逐局比分；cue 通过不可变副本冻结，不受后续得分影响
        List<SetScore> setScores = new ArrayList<>();
        setScores.add(new SetScore(1, 0, 0));

        scoreboardCues.add(new ScoreboardCue(0, 0, 1, 0, 0, 0, 0, false, setScores));

        int setNo = 1;
        int scoreA = 0;
        int scoreB = 0;
        int setWinsA = 0;
        int setWinsB = 0;
        boolean matchCompleted = false;

        for (ScoreEvent event : sorted) {
            if (matchCompleted) {
                throw new DomainValidationException("比赛已经结束，不能继续记录得分");
            }

            if (event.playerSide() == PlayerSide.A) {
                scoreA++;
            } else {
                scoreB++;
            }
            updateCurrentSet(setScores, setNo, scoreA, scoreB);

            derivedEvents.add(new DerivedScoreEvent(
                    event.id(), event.videoTimeMs(), event.playerSide(), event.sequenceNo(),
                    setNo, scoreA, scoreB, setWinsA, setWinsB
            ));

            scoreboardCues.add(new ScoreboardCue(
                    event.videoTimeMs(), event.sequenceNo(), setNo, scoreA, scoreB,
                    setWinsA, setWinsB, false, setScores
            ));

            boolean setFinished = (scoreA >= 11 || scoreB >= 11) && Math.abs(scoreA - scoreB) >= 2;
            if (setFinished) {
                PlayerSide winner = scoreA > scoreB ? PlayerSide.A : PlayerSide.B;
                completedSets.add(new SetSummary(setNo, scoreA, scoreB, winner));
                if (winner == PlayerSide.A) {
                    setWinsA++;
                } else {
                    setWinsB++;
                }
                if (setWinsA == targetWins || setWinsB == targetWins) {
                    matchCompleted = true;
                    // 比赛结束：最后一局保留最终比分，不追加下一局
                    scoreboardCues.add(new ScoreboardCue(
                            event.videoTimeMs(), event.sequenceNo(), setNo, scoreA, scoreB,
                            setWinsA, setWinsB, true, setScores
                    ));
                } else {
                    setNo++;
                    scoreA = 0;
                    scoreB = 0;
                    setScores.add(new SetScore(setNo, 0, 0));
                    scoreboardCues.add(new ScoreboardCue(
                            event.videoTimeMs(), event.sequenceNo(), setNo, 0, 0,
                            setWinsA, setWinsB, false, setScores
                    ));
                }
            }
        }

        return new ReplayResult(completedSets, derivedEvents, setNo, scoreA, scoreB,
                setWinsA, setWinsB, matchCompleted, scoreboardCues);
    }

    /**
     * 更新逐局列表中最后一局（即当前局）的比分
     */
    private void updateCurrentSet(List<SetScore> setScores, int setNo, int scoreA, int scoreB) {
        int lastIndex = setScores.size() - 1;
        SetScore current = setScores.get(lastIndex);
        setScores.set(lastIndex, new SetScore(current.setNumber(), scoreA, scoreB));
    }
}
