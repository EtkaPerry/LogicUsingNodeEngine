package com.etka.lune.config;

import com.etka.lune.Links;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /**
     * Every line of the page resolves to words rather than to its own key.
     *
     * <p>{@link com.etka.lune.util.Lang} returns the key when nothing has been written for it, so
     * a typo in one of these turns the sentence somebody is agreeing to into
     * {@code lune.terms.point.fair.label} - which is still a tick box, still above an Accept
     * button, and no longer consent to anything. That failure is silent everywhere else.</p>
     */
    @Test
    void everyLineOnThePageIsRealText() {
        assertNotEquals("lune.terms.title", Terms.title());
        assertNotEquals("lune.terms.intro", Terms.intro());
        assertNotEquals("lune.terms.footnote", Terms.footnote());
        for (Terms.Point point : Terms.POINTS) {
            assertNotEquals(point.labelKey(), point.label(),
                    point.labelKey() + " has no line written for it");
            assertNotEquals(point.detailKey(), point.detail(),
                    point.detailKey() + " has no line written for it");
        }
    }

    /**
     * The points hold keys, not sentences.
     *
     * <p>Which is what lets the page be read in the player's own language, and what stops the
     * wording being frozen at class-load in whichever language happened to be open first.</p>
     */
    @Test
    void thePointsAreStoredAsKeys() {
        for (Terms.Point point : Terms.POINTS) {
            assertTrue(point.labelKey().startsWith("lune.terms."), point.labelKey());
            assertTrue(point.detailKey().startsWith("lune.terms."), point.detailKey());
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
