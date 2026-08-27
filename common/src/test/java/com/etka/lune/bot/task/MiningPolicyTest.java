package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningPolicyTest {

    @Test
    void levelFirstAvoidsUnnecessaryVerticalMovement() {
        double sameLevel = MiningPolicy.levelFirstScore(0, 10, 0, 4, 10, 0);
        double vertical = MiningPolicy.levelFirstScore(0, 10, 0, 0, 13, 0);

        assertTrue(sameLevel < vertical);
    }

    @Test
    void amountAndRadiusContextsAreBounded() {
        assertEquals("few", MiningPolicy.amountBucket(4));
        assertEquals("large", MiningPolicy.amountBucket(30));
        assertEquals("wide", MiningPolicy.radiusBucket(64));
    }
}
