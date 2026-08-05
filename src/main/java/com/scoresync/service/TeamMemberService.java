package com.scoresync.service;

import com.scoresync.domain.TeamMember;
import com.scoresync.repository.TeamMemberRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 队员服务
 * 提供队员的增删改查操作
 */
@Service
public class TeamMemberService {
    private final TeamMemberRepository repository;

    public TeamMemberService(TeamMemberRepository repository) {
        this.repository = repository;
    }

    /**
     * 获取所有队员
     */
    public List<TeamMember> getAll() {
        return repository.findAll();
    }

    /**
     * 根据ID获取队员
     */
    public TeamMember getById(long id) {
        return repository.findById(id);
    }

    /**
     * 创建新队员
     */
    public TeamMember create(String name) {
        long id = repository.insert(name);
        return new TeamMember(id, name);
    }

    /**
     * 更新队员名称
     */
    public void update(long id, String name) {
        repository.update(id, name);
    }

    /**
     * 删除队员
     */
    public void delete(long id) {
        repository.delete(id);
    }
}