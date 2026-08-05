package com.scoresync.domain;

/**
 * 视频来源类型
 * 表示视频是如何添加到系统中的
 * <p>
 * SCANNED - 通过扫描目录自动发现
 * PICKED  - 用户手动选择添加
 */
public enum VideoOriginType {
    SCANNED, PICKED
}
