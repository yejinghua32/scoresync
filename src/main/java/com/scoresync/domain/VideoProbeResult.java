package com.scoresync.domain;

/**
 * 视频探测结果
 * 通过 FFprobe 获取的视频元数据，包括分辨率、时长、帧率及音频信息
 *
 * @param width      视频宽度（像素）
 * @param height     视频高度（像素）
 * @param durationMs 视频时长（毫秒）
 * @param frameRate  帧率（如 "30/1"）
 * @param hasAudio   是否包含音频轨道
 */
public record VideoProbeResult(int width, int height, long durationMs,
                               String frameRate, boolean hasAudio) {
}
