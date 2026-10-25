package com.scoresync.service;

import com.scoresync.domain.TeamMember;
import com.scoresync.repository.TeamMemberRepository;
import com.scoresync.support.DomainValidationException;
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
     * 名称重复或为空时抛出业务校验异常，避免数据库唯一约束报错
     *
     * @param name 队员名称
     * @return 新建的队员
     */
    public TeamMember create(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new DomainValidationException("队员名称不能为空");
        }
        if (repository.findByName(trimmed).isPresent()) {
            throw new DomainValidationException("队员名称已存在");
        }
        long id = repository.insert(trimmed);
        return new TeamMember(id, trimmed);
    }

    /**
     * 确保队名已登记在队员列表中
     * 已存在时直接返回，不会重复登记；用于创建项目时自动收录自定义队名
     *
     * @param name 队员名称
     * @return 已登记的队员
     */
    public TeamMember ensureExists(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new DomainValidationException("队员名称不能为空");
        }
        return repository.findByName(trimmed).orElseGet(() -> {
            long id = repository.insert(trimmed);
            return new TeamMember(id, trimmed);
        });
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