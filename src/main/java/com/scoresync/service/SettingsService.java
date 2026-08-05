package com.scoresync.service;

import com.scoresync.domain.Settings;
import com.scoresync.repository.SettingsRepository;
import org.springframework.stereotype.Service;

/**
 * 设置服务
 * 负责应用设置的读取和保存
 */
@Service
public class SettingsService {

    private final SettingsRepository settingsRepository;

    public SettingsService(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    /**
     * 获取当前设置
     */
    public Settings getSettings() {
        return settingsRepository.find();
    }

    /**
     * 保存设置
     */
    public void saveSettings(Settings settings) {
        settingsRepository.save(settings);
    }
}