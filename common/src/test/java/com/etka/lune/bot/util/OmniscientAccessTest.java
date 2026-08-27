package com.etka.lune.bot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OmniscientAccessTest {

    @Test
    void allowsThePlayerOwnedIntegratedWorld() {
        assertTrue(OmniscientAccess.isAllowed(true, false));
    }

    @Test
    void allowsAnOperatorOnADedicatedServer() {
        assertTrue(OmniscientAccess.isAllowed(false, true));
    }

    @Test
    void deniesAnOrdinaryDedicatedServerPlayer() {
        assertFalse(OmniscientAccess.isAllowed(false, false));
    }
}
