package com.scoresync.domain;

/**
 * 队员
 * 简单的队员信息记录，用于团队相关功能
 *
 * @param id   唯一标识符
 * @param name 姓名
 */
public record TeamMember(long id, String name) {
}
