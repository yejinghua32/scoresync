package com.scoresync.domain;

import java.util.List;

/**
 * 比分牌快照
 * 某一时刻比分牌的完整状态，用于渲染或预览
 *
 * @param template       使用的模板
 * @param playerA        A 方选手名称
 * @param playerB        B 方选手名称
 * @param scoreA         A 方当前得分
 * @param scoreB         B 方当前得分
 * @param setNumber      当前局号
 * @param setWinsA       A 方已胜局数
 * @param setWinsB       B 方已胜局数
 * @param matchCompleted 比赛是否已完成
 * @param firstServer    首局发球方
 * @param frameWidth     视频帧宽度
 * @param frameHeight    视频帧高度
 * @param setScores      截至该时间点已开始的逐局比分（按局号升序，不可变）
 * @param highlightedSide 当前需要突出显示的得分方；无高亮时为 null
 * @param projectName    项目名称，用于队名列表头
 * @param showProjectName 队名列表头是否显示项目名称，默认不显示
 */
public record ScoreboardSnapshot(ScoreboardTemplate template, String playerA, String playerB,
                                 int scoreA, int scoreB, int setNumber,
                                 int setWinsA, int setWinsB, boolean matchCompleted,
                                 PlayerSide firstServer,
                                 int frameWidth, int frameHeight,
                                 List<SetScore> setScores,
                                 PlayerSide highlightedSide,
                                 String projectName, boolean showProjectName) {

    public ScoreboardSnapshot {
        setScores = setScores == null ? List.of() : List.copyOf(setScores);
        projectName = projectName == null ? "" : projectName;
    }

    /**
     * 保留既有调用方式：逐局比分已知、无高亮
     */
    public ScoreboardSnapshot(ScoreboardTemplate template, String playerA, String playerB,
                              int scoreA, int scoreB, int setNumber,
                              int setWinsA, int setWinsB, boolean matchCompleted,
                              PlayerSide firstServer, int frameWidth, int frameHeight,
                              List<SetScore> setScores) {
        this(template, playerA, playerB, scoreA, scoreB, setNumber, setWinsA, setWinsB,
                matchCompleted, firstServer, frameWidth, frameHeight, setScores, null, "", false);
    }

    /**
     * 保留既有调用方式：逐局比分已知、有高亮但不显示项目名称
     */
    public ScoreboardSnapshot(ScoreboardTemplate template, String playerA, String playerB,
                              int scoreA, int scoreB, int setNumber,
                              int setWinsA, int setWinsB, boolean matchCompleted,
                              PlayerSide firstServer, int frameWidth, int frameHeight,
                              List<SetScore> setScores, PlayerSide highlightedSide) {
        this(template, playerA, playerB, scoreA, scoreB, setNumber, setWinsA, setWinsB,
                matchCompleted, firstServer, frameWidth, frameHeight, setScores, highlightedSide, "", false);
    }

    /**
     * 仅含当前局的便捷构造，逐局数据与当前局号和比分保持一致
     */
    public ScoreboardSnapshot(ScoreboardTemplate template, String playerA, String playerB,
                              int scoreA, int scoreB, int setNumber,
                              int setWinsA, int setWinsB, boolean matchCompleted,
                              PlayerSide firstServer, int frameWidth, int frameHeight) {
        this(template, playerA, playerB, scoreA, scoreB, setNumber, setWinsA, setWinsB,
                matchCompleted, firstServer, frameWidth, frameHeight,
                List.of(new SetScore(setNumber, scoreA, scoreB)), null, "", false);
    }
}
