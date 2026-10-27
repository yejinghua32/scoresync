package com.scoresync.domain;

import java.time.Instant;

/**
 * 剪辑项目
 * 一个剪辑项目对应一个视频资产，可反复修改保留/舍弃区间
 *
 * @param id                唯一标识符
 * @param name              项目名称
 * @param videoAssetId      源视频资产 ID
 * @param largeStep         大步长（上下键每次选取的帧数）
 * @param smallStep         小步长（左右键每次增减的帧数）
 * @param sourceFileSize    建立项目时源文件的大小（字节），用于检测源文件变化
 * @param sourceModifiedAt  建立项目时源文件的修改时间（毫秒），用于检测源文件变化
 * @param totalFrames       源视频总帧数（帧索引建立后才有值，可为 null）
 * @param frameIndexStatus  帧索引状态
 * @param createdAt         创建时间
 * @param updatedAt         最后更新时间
 */
public record ClipProject(long id, String name, long videoAssetId, int largeStep, int smallStep,
                           long sourceFileSize, long sourceModifiedAt, Integer totalFrames,
                           ClipFrameIndexStatus frameIndexStatus, Instant createdAt, Instant updatedAt) {
}
