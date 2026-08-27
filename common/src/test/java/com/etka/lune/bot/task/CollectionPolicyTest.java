package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionPolicyTest {

    @Test
    void worksiteStrategyDoesNotGetDistractedByTheMovingPlayer() {
        assertFalse(CollectionPolicy.checksImmediatePocket(CollectionPolicy.WORKSITE_FIRST));
        assertFalse(CollectionPolicy.ranksFromPlayer(CollectionPolicy.WORKSITE_FIRST));
    }

    @Test
    void nearestStrategyTracksTheLivePlayerPosition() {
        assertTrue(CollectionPolicy.checksImmediatePocket(CollectionPolicy.NEAREST_FIRST));
        assertTrue(CollectionPolicy.ranksFromPlayer(CollectionPolicy.NEAREST_FIRST));
    }
}
