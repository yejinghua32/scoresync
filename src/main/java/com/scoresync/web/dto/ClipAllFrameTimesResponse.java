package com.scoresync.web.dto;

/**
 * 全部帧时间映射响应体
 * 一次给出源视频每一帧对应的播放时间（毫秒），
 * 前端用二分查找换算位置，避免逐段请求
 *
 * @param totalFrames 总帧数
 * @param times       每帧对应的播放时间（毫秒），下标即帧序号
 */
public record ClipAllFrameTimesResponse(int totalFrames, long[] times) {
}
