package com.etka.lune.config;

import com.etka.lune.Links;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acceptance rules, tested apart from the config file they are stored in and the screen they
 * are shown on. Both of those need a Minecraft client; the decisions do not.
 */
class TermsTest {

    /** A fresh install has accepted nothing, whatever the shipped defaults say. */
    @Test
    void aFreshInstallHasNotAccepted() {
        assertFalse(Terms.accepted(new BotConfig().acceptedTermsVersion));
    }

    @Test
    void acceptingTheCurrentWordingCounts() {
        assertTrue(Terms.accepted(Terms.VERSION));
    }

    /**
     * The point of versioning the wording: an acceptance of an older, weaker set of points is not
     * an acceptance of the current one, so bumping the version asks everybody again.
     */
    @Test
    void anOlderAcceptanceDoesNotCarryForward() {
        assertFalse(Terms.accepted(Terms.VERSION - 1));
    }

    /**
     * A later version stays accepted. Someone who has downgraded the mod agreed to strictly more
     * than they are now being shown, and re-asking them would be theatre.
     */
    @Test
    void aLaterAcceptanceStillCounts() {
        assertTrue(Terms.accepted(Terms.VERSION + 1));
    }

    /**
     * The three points are what the player is agreeing to, so they are part of the contract rather
     * than decoration: losing one silently would change what acceptance means without changing the
     * version that records it.
     */
    @Test
    void thereAreThreePointsAndEachSaysSomething() {
        assertEquals(3, Terms.POINTS.size());
        for (Terms.Point point : Terms.POINTS) {
            assertFalse(point.label().isBlank());
            assertFalse(point.detail().isBlank());
        }
    }

    /** Two screens offer to open these in a browser, so a typo in one is a dead button. */
    @Test
    void theLinksPointWhereTheySay() {
        assertTrue(Links.REPOSITORY.startsWith("https://"));
        assertTrue(Links.LICENSE.endsWith("/LICENSE"));
        assertTrue(Links.ISSUES.endsWith("/issues"));
    }
}
