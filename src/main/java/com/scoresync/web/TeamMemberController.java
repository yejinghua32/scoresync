package com.scoresync.web;

/**
 * 队员管理控制器
 * 负责运动员/队员的增删改查操作
 */

import com.scoresync.domain.TeamMember;
import com.scoresync.service.TeamMemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/team-members")
public class TeamMemberController {
    private final TeamMemberService service;

    public TeamMemberController(TeamMemberService service) {
        this.service = service;
    }

    /**
     * 获取所有队员列表
     */
    @GetMapping
    public List<TeamMember> getAll() {
        return service.getAll();
    }

    /**
     * 根据ID获取队员信息
     */
    @GetMapping("/{id}")
    public TeamMember getById(@PathVariable long id) {
        return service.getById(id);
    }

    /**
     * 创建新队员
     */
    @PostMapping
    public ResponseEntity<TeamMember> create(@RequestBody CreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("队员名称不能为空");
        }
        TeamMember created = service.create(request.name().trim());
        return ResponseEntity.created(URI.create("/api/team-members/" + created.id())).body(created);
    }

    /**
     * 更新队员名称
     */
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable long id, @RequestBody CreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("队员名称不能为空");
        }
        service.update(id, request.name().trim());
        return ResponseEntity.noContent().build();
    }

    /**
     * 删除队员
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(String name) {
    }
}
