package com.scoresync.domain;

/**
 * 选手侧枚举
 * 表示比赛的 A 方或 B 方
 * <p>
 * A - A 方
 * B - B 方
 */
public enum PlayerSide {
    A, B;

    /**
     * 获取对方
     *
     * @return 另一侧的 PlayerSide
     */
    public PlayerSide other() {
        return this == A ? B : A;
    }
}
