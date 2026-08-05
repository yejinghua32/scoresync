package com.scoresync.service;

import com.scoresync.support.DomainValidationException;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.nio.file.Path;
import java.util.Optional;

/**
 * AWT原生文件选择器实现
 * 使用Java AWT的FileDialog组件实现平台原生的视频文件选择
 */
@Component
public class AwtNativeFilePickerService implements NativeFilePickerService {

    /**
     * 打开原生文件选择对话框，仅允许选择.mp4/.webm/.mov视频文件
     *
     * @return 选中的文件路径，若用户取消则返回空
     */
    @Override
    public Optional<Path> chooseVideoFile() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            throw new DomainValidationException("当前运行环境不支持本机文件选择器");
        }
        FileDialog dialog = new FileDialog((Frame) null, "选择视频文件", FileDialog.LOAD);
        dialog.setFilenameFilter((dir, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov");
        });
        dialog.setVisible(true);
        String file = dialog.getFile();
        String directory = dialog.getDirectory();
        if (file == null || directory == null) {
            return Optional.empty();
        }
        return Optional.of(Path.of(directory, file));
    }
}
