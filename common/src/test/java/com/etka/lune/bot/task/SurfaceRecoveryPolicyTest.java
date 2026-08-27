package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceRecoveryPolicyTest {

    @Test
    void aPhaseStillRecoversWhileTheRecoveryHasSomethingToOffer() {
        assertTrue(SurfaceRecoveryPolicy.shouldRecover(true, false));
    }

    @Test
    void aPhaseOnDryGroundNeverAsksForRecovery() {
        assertFalse(SurfaceRecoveryPolicy.shouldRecover(false, false));
        assertFalse(SurfaceRecoveryPolicy.shouldRecover(false, true));
    }

    @Test
    void anExhaustedRecoveryStopsConsumingTheRetryBudget() {
        assertFalse(SurfaceRecoveryPolicy.shouldRecover(true, true));
    }

    @Test
    void aFailedRecoveryIsUnavailable() {
        assertTrue(SurfaceRecoveryPolicy.recoveryUnavailable(true, false));
        assertTrue(SurfaceRecoveryPolicy.recoveryUnavailable(true, true));
    }

    @Test
    void aSuccessThatChangedNothingIsAlsoUnavailable() {
        assertTrue(SurfaceRecoveryPolicy.recoveryUnavailable(false, true));
    }

    @Test
    void aSuccessThatReachedDryGroundLeavesTheGateOpen() {
        assertFalse(SurfaceRecoveryPolicy.recoveryUnavailable(false, false));
    }
}
