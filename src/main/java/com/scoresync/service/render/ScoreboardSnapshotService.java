package com.scoresync.service.render;

import com.scoresync.domain.MatchProject;
import com.scoresync.domain.ScoreboardCue;
import com.scoresync.domain.ScoreboardSnapshot;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 计分板快照服务
 * 根据指定时间点从计分板cue序列中获取对应的快照数据
 */
@Service
public class ScoreboardSnapshotService {

    /**
     * 获取指定时刻的计分板快照
     *
     * @param project     比赛项目，包含选手信息和模板配置
     * @param cues        所有计分板cue列表，按时间排序
     * @param timeMs      指定时刻（毫秒）
     * @param frameWidth  视频帧宽度
     * @param frameHeight 视频帧高度
     * @return 指定时刻的计分板快照
     */
    public ScoreboardSnapshot at(MatchProject project, List<ScoreboardCue> cues, long timeMs, int frameWidth, int frameHeight) {
        long effectiveTime = Math.max(0, timeMs);

        // 同一时间与事件序号可能出现多个 cue（如局胜点后先结算再开新局），
        // 此时以列表中最后生成的 cue 为准，因此这里用 >= 覆盖已有选择。
        Comparator<ScoreboardCue> byTime = Comparator.comparingLong(ScoreboardCue::startTimeMs)
                .thenComparingLong(ScoreboardCue::sequenceNo);
        ScoreboardCue lastCue = null;
        for (ScoreboardCue cue : cues) {
            if (cue.startTimeMs() > effectiveTime) {
                continue;
            }
            if (lastCue == null || byTime.compare(cue, lastCue) >= 0) {
                lastCue = cue;
            }
        }
        if (lastCue == null) {
            lastCue = cues.get(cues.size() - 1);
        }

        return new ScoreboardSnapshot(
                project.scoreboardTemplate(),
                project.playerA(),
                project.playerB(),
                lastCue.scoreA(),
                lastCue.scoreB(),
                lastCue.setNumber(),
                lastCue.setWinsA(),
                lastCue.setWinsB(),
                lastCue.matchCompleted(),
                project.firstServer(),
                frameWidth,
                frameHeight,
                lastCue.setScores()
        );
    }
}
