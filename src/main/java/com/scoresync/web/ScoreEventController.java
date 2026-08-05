package com.scoresync.web;

/**
 * 比分事件控制器
 * 负责管理比赛中的得分事件（添加、修改、删除）
 */

import com.scoresync.domain.EditorState;
import com.scoresync.service.ScoreEventService;
import com.scoresync.web.dto.CreateScoreEventRequest;
import com.scoresync.web.dto.UpdateScoreEventRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ScoreEventController {

    private final ScoreEventService scoreEventService;

    public ScoreEventController(ScoreEventService scoreEventService) {
        this.scoreEventService = scoreEventService;
    }

    /**
     * 添加新的得分事件（指定视频时间戳和得分方）
     */
    @PostMapping("/projects/{id}/events")
    public ResponseEntity<EditorState> addEvent(@PathVariable long id,
                                                @Valid @RequestBody CreateScoreEventRequest request) {
        EditorState state = scoreEventService.add(id, request.videoTimeMs(), request.playerSide());
        return ResponseEntity.status(HttpStatus.CREATED).body(state);
    }

    /**
     * 修改已有得分事件的时间或得分方
     */
    @PatchMapping("/events/{id}")
    public ResponseEntity<EditorState> updateEvent(@PathVariable long id,
                                                   @Valid @RequestBody UpdateScoreEventRequest request) {
        EditorState state = scoreEventService.update(id, request.videoTimeMs(), request.playerSide());
        return ResponseEntity.ok().body(state);
    }

    /**
     * 撤销最后一个得分事件
     */
    @DeleteMapping("/projects/{id}/events/last")
    public ResponseEntity<EditorState> undoLastEvent(@PathVariable long id) {
        EditorState state = scoreEventService.undoLast(id);
        return ResponseEntity.ok().body(state);
    }

    /**
     * 删除指定的得分事件
     */
    @DeleteMapping("/events/{id}")
    public ResponseEntity<EditorState> deleteEvent(@PathVariable long id) {
        EditorState state = scoreEventService.delete(id);
        return ResponseEntity.ok().body(state);
    }
}
