package com.scoresync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ScoreSyncApplicationTest {
    @Test
    void applicationClassExists() {
        assertDoesNotThrow(() -> Class.forName("com.scoresync.ScoreSyncApplication"));
    }
}
