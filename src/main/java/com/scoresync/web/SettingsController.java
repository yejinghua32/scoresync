package com.scoresync.web;

/**
 * 系统设置与记分板模板控制器
 * 负责应用全局设置和记分板样式模板的管理
 */

import com.scoresync.domain.ScoreboardTemplateConfig;
import com.scoresync.domain.Settings;
import com.scoresync.service.ScoreboardTemplateService;
import com.scoresync.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SettingsController {

    private final SettingsService settingsService;
    private final ScoreboardTemplateService templateService;

    public SettingsController(SettingsService settingsService, ScoreboardTemplateService templateService) {
        this.settingsService = settingsService;
        this.templateService = templateService;
    }

    /**
     * 获取当前应用设置
     */
    @GetMapping("/settings")
    public Settings getSettings() {
        return settingsService.getSettings();
    }

    /**
     * 保存应用设置
     */
    @PutMapping("/settings")
    public ResponseEntity<Void> saveSettings(@RequestBody Settings settings) {
        settingsService.saveSettings(settings);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取所有记分板模板配置
     */
    @GetMapping("/scoreboard-templates")
    public List<ScoreboardTemplateConfig> listTemplates() {
        return templateService.findAll();
    }

    /**
     * 创建新的记分板模板
     */
    @PostMapping("/scoreboard-templates")
    public ResponseEntity<ScoreboardTemplateConfig> createTemplate(@RequestBody ScoreboardTemplateConfig config) {
        ScoreboardTemplateConfig created = templateService.create(config);
        return ResponseEntity.status(201).body(created);
    }

    /**
     * 更新记分板模板配置
     */
    @PutMapping("/scoreboard-templates/{id}")
    public ResponseEntity<ScoreboardTemplateConfig> updateTemplate(@PathVariable long id,
                                                                   @RequestBody ScoreboardTemplateConfig config) {
        return ResponseEntity.ok(templateService.update(id, config));
    }

    /**
     * 删除记分板模板
     */
    @DeleteMapping("/scoreboard-templates/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable long id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
