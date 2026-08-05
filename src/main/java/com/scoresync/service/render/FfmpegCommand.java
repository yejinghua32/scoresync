package com.scoresync.service.render;

import java.nio.file.Path;
import java.util.List;

/**
 * FFmpeg命令记录
 * 包含执行FFmpeg所需的命令行参数、滤镜脚本路径和临时输出路径
 *
 * @param arguments       FFmpeg命令行参数列表
 * @param filterScript    滤镜脚本文件路径
 * @param temporaryOutput 临时输出文件路径
 */
public record FfmpegCommand(List<String> arguments, Path filterScript, Path temporaryOutput) {
}
