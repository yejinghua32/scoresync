package com.scoresync.domain;

import java.util.List;

/**
 * 清空视频库的结果
 *
 * @param deleted 已移除登记信息的视频数量
 * @param skipped 仍在项目中使用、无法移除的视频名称
 */
public record VideoBulkDeleteResult(int deleted, List<String> skipped) {
}
