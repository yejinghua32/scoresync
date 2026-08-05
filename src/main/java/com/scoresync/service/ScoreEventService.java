package com.scoresync.service;

import com.scoresync.domain.EditorState;
import com.scoresync.domain.MatchProject;
import com.scoresync.domain.PlayerSide;
import com.scoresync.domain.ScoreEvent;
import com.scoresync.repository.MatchProjectRepository;
import com.scoresync.repository.ScoreEventRepository;
import com.scoresync.repository.VideoAssetRepository;
import com.scoresync.support.DomainValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 得分事件服务
 * 处理比赛得分事件的添加、修改、删除、撤销等操作
 * 使用项目级别的锁保证并发安全
 */
@Service
public class ScoreEventService {

    private final MatchProjectRepository projectRepository;
    private final VideoAssetRepository videoAssetRepository;
    private final ScoreEventRepository eventRepository;
    private final ScoreReplayService replayService;
    private final ProjectService projectService;
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ScoreEventService(MatchProjectRepository projectRepository,
                             VideoAssetRepository videoAssetRepository,
                             ScoreEventRepository eventRepository,
                             ScoreReplayService replayService,
                             ProjectService projectService) {
        this.projectRepository = projectRepository;
        this.videoAssetRepository = videoAssetRepository;
        this.eventRepository = eventRepository;
        this.replayService = replayService;
        this.projectService = projectService;
    }

    private ReentrantLock lockFor(long projectId) {
        return locks.computeIfAbsent(projectId, k -> new ReentrantLock());
    }

    /**
     * 添加得分事件
     *
     * @param projectId   项目ID
     * @param videoTimeMs 视频时间戳（毫秒）
     * @param playerSide  得分方
     */
    @Transactional
    public EditorState add(long projectId, long videoTimeMs, PlayerSide playerSide) {
        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            MatchProject project = projectRepository.findById(projectId);
            if (videoTimeMs < 0 || videoTimeMs > project.durationMs()) {
                throw new DomainValidationException("得分时间必须位于视频时长范围内");
            }

            List<ScoreEvent> existingEvents = eventRepository.findByMatchId(projectId);
            List<ScoreEvent> candidateEvents = new ArrayList<>(existingEvents);
            long fakeId = System.currentTimeMillis();
            ScoreEvent newEvent = new ScoreEvent(fakeId, projectId, videoTimeMs, playerSide,
                    existingEvents.size() + 1, java.time.Instant.now(), java.time.Instant.now());
            candidateEvents.add(newEvent);

            replayService.replay(project.targetWins(), candidateEvents);

            eventRepository.insert(projectId, videoTimeMs, playerSide);

            return buildEditorState(projectId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 更新已有得分事件的时间戳和得分方
     */
    @Transactional
    public EditorState update(long eventId, long videoTimeMs, PlayerSide playerSide) {
        ScoreEvent existingEvent = eventRepository.findById(eventId);
        long projectId = existingEvent.matchId();

        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            MatchProject project = projectRepository.findById(projectId);
            if (videoTimeMs < 0 || videoTimeMs > project.durationMs()) {
                throw new DomainValidationException("得分时间必须位于视频时长范围内");
            }

            List<ScoreEvent> existingEvents = eventRepository.findByMatchId(projectId);
            List<ScoreEvent> candidateEvents = new ArrayList<>();
            for (ScoreEvent e : existingEvents) {
                if (e.id() == eventId) {
                    candidateEvents.add(new ScoreEvent(e.id(), e.matchId(), videoTimeMs, playerSide,
                            e.sequenceNo(), e.createdAt(), java.time.Instant.now()));
                } else {
                    candidateEvents.add(e);
                }
            }

            replayService.replay(project.targetWins(), candidateEvents);

            eventRepository.update(eventId, videoTimeMs, playerSide);

            return buildEditorState(projectId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除指定得分事件
     */
    @Transactional
    public EditorState delete(long eventId) {
        ScoreEvent existingEvent = eventRepository.findById(eventId);
        long projectId = existingEvent.matchId();

        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            MatchProject project = projectRepository.findById(projectId);

            List<ScoreEvent> existingEvents = eventRepository.findByMatchId(projectId);
            List<ScoreEvent> candidateEvents = existingEvents.stream()
                    .filter(e -> e.id() != eventId)
                    .toList();

            replayService.replay(project.targetWins(), candidateEvents);

            eventRepository.delete(eventId);

            return buildEditorState(projectId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 撤销最后一次得分事件
     */
    @Transactional
    public EditorState undoLast(long projectId) {
        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            MatchProject project = projectRepository.findById(projectId);

            ScoreEvent lastEvent = eventRepository.findMostRecentByMatchId(projectId)
                    .orElseThrow(() -> new DomainValidationException("没有可撤销的得分事件"));

            List<ScoreEvent> existingEvents = eventRepository.findByMatchId(projectId);
            List<ScoreEvent> candidateEvents = existingEvents.stream()
                    .filter(e -> e.id() != lastEvent.id())
                    .toList();

            replayService.replay(project.targetWins(), candidateEvents);

            eventRepository.delete(lastEvent.id());

            return buildEditorState(projectId);
        } finally {
            lock.unlock();
        }
    }

    private EditorState buildEditorState(long projectId) {
        return projectService.editorState(projectId);
    }
}
