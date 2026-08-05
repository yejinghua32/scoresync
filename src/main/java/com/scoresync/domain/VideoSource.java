package com.scoresync.domain;

import java.time.Instant;

/**
 * 视频扫描源
 * 表示一个视频扫描根目录，包含ID、根路径、启用状态及时间戳
 *
 * @param id        唯一标识符
 * @param rootPath  扫描根目录的绝对路径
 * @param enabled   是否启用扫描
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record VideoSource(long id, String rootPath, boolean enabled, Instant createdAt, Instant updatedAt) {
}
