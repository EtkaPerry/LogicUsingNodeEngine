package com.etka.lune.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The share policy is agreed to once and asked for again only when it changes.
 *
 * <p>Tested on the stored number alone: the config file cannot be reached from the common tests,
 * where no loader runs.</p>
 */
class SharePolicyTest {

    @Test
    void aFreshInstallHasNotAgreed() {
        assertFalse(SharePolicy.accepted(0));
    }

    @Test
    void agreeingToThisVersionHoldsUntilItChanges() {
        assertTrue(SharePolicy.accepted(SharePolicy.VERSION));
        assertTrue(SharePolicy.accepted(SharePolicy.VERSION + 1));
        assertFalse(SharePolicy.accepted(SharePolicy.VERSION - 1));
    }
}
