package com.scoresync.domain;

import java.util.List;

/**
 * 比分牌提示点
 * 在特定时间点需要渲染的比分牌状态，用于生成渲染指令序列
 *
 * @param startTimeMs    开始时间（毫秒）
 * @param sequenceNo     对应的事件序号
 * @param setNumber      当前局号
 * @param scoreA         A 方得分
 * @param scoreB         B 方得分
 * @param setWinsA       A 方已胜局数
 * @param setWinsB       B 方已胜局数
 * @param matchCompleted 比赛是否已完成
 * @param setScores      截至该时间点已开始的逐局比分（按局号升序，不可变）
 * @param scoredSide     产生该提示点的得分方；初始 0:0 提示点为 null
 */
public record ScoreboardCue(long startTimeMs, long sequenceNo, int setNumber,
                            int scoreA, int scoreB, int setWinsA, int setWinsB,
                            boolean matchCompleted, List<SetScore> setScores,
                            PlayerSide scoredSide) {

    public ScoreboardCue {
        setScores = setScores == null ? List.of() : List.copyOf(setScores);
    }

    /**
     * 保留既有调用方式：逐局比分已知、得分方未知
     */
    public ScoreboardCue(long startTimeMs, long sequenceNo, int setNumber,
                         int scoreA, int scoreB, int setWinsA, int setWinsB,
                         boolean matchCompleted, List<SetScore> setScores) {
        this(startTimeMs, sequenceNo, setNumber, scoreA, scoreB, setWinsA, setWinsB,
                matchCompleted, setScores, null);
    }

    /**
     * 仅含当前局的便捷构造，逐局数据与当前局号和比分保持一致
     */
    public ScoreboardCue(long startTimeMs, long sequenceNo, int setNumber,
                         int scoreA, int scoreB, int setWinsA, int setWinsB,
                         boolean matchCompleted) {
        this(startTimeMs, sequenceNo, setNumber, scoreA, scoreB, setWinsA, setWinsB,
                matchCompleted, List.of(new SetScore(setNumber, scoreA, scoreB)), null);
    }
}
