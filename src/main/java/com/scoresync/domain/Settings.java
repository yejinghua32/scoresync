package com.scoresync.domain;

/**
 * 应用设置
 * 存储全局应用配置，目前包含参赛选手名称
 *
 * @param playerAName A 方选手名称
 * @param playerBName B 方选手名称
 */
public record Settings(
        String playerAName,
        String playerBName
) {
}
