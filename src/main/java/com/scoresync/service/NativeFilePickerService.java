package com.scoresync.service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 原生文件选择器接口
 * 平台相关的文件选择实现
 */
public interface NativeFilePickerService {
    Optional<Path> chooseVideoFile();
}
