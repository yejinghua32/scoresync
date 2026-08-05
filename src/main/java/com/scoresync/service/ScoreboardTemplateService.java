package com.scoresync.service;

import com.scoresync.domain.ScoreboardTemplateConfig;
import com.scoresync.repository.ScoreboardTemplateRepository;
import com.scoresync.support.DomainNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 比分牌模板服务
 * 管理比赛比分牌的视觉配置模板（如透明度、背景色、文字颜色等）
 */
@Service
public class ScoreboardTemplateService {

    private final ScoreboardTemplateRepository repository;

    public ScoreboardTemplateService(ScoreboardTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * 获取所有模板
     */
    public List<ScoreboardTemplateConfig> findAll() {
        return repository.findAll();
    }

    /**
     * 根据ID获取模板
     */
    public ScoreboardTemplateConfig findById(long id) {
        ScoreboardTemplateConfig config = repository.findById(id);
        if (config == null) {
            throw new DomainNotFoundException("模板不存在");
        }
        return config;
    }

    /**
     * 创建新模板
     */
    public ScoreboardTemplateConfig create(ScoreboardTemplateConfig config) {
        long id = repository.insert(config);
        return repository.findById(id);
    }

    /**
     * 更新指定模板
     */
    public ScoreboardTemplateConfig update(long id, ScoreboardTemplateConfig config) {
        ScoreboardTemplateConfig existing = repository.findById(id);
        if (existing == null) {
            throw new DomainNotFoundException("模板不存在");
        }
        repository.update(new ScoreboardTemplateConfig(id, config.name(), config.opacity(),
                config.bgColor(), config.textColor()));
        return repository.findById(id);
    }

    /**
     * 删除指定模板
     */
    public void delete(long id) {
        if (repository.findById(id) == null) {
            throw new DomainNotFoundException("模板不存在");
        }
        repository.delete(id);
    }
}
