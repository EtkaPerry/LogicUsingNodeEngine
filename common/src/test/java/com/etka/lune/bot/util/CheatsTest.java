package com.etka.lune.bot.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that make a cheat a cheat rather than a setting: it needs the world's permission to go
 * on, it never survives the connection that granted it, and it is never anywhere on disk.
 */
class CheatsTest {

    private static final Object ONE_WORLD = new Object();
    private static final Object ANOTHER_WORLD = new Object();

    @BeforeEach
    void joinAWorld() {
        // Through nothing first: binding straight to ONE_WORLD would be a no-op after a test that
        // left it bound, and the next test would inherit its switches.
        Cheats.bind(null);
        Cheats.bind(ONE_WORLD);
    }

    @Test
    void anAllowedWorldCanSwitchAModeOn() {
        assertTrue(Cheats.set(Cheats.Mode.MINING, true, true));
        assertTrue(Cheats.isOn(Cheats.Mode.MINING));
        assertTrue(Cheats.isActive(Cheats.Mode.MINING, true));
    }

    @Test
    void anOrdinaryServerPlayerIsRefused() {
        assertFalse(Cheats.set(Cheats.Mode.MINING, true, false));
        assertFalse(Cheats.isOn(Cheats.Mode.MINING));
    }

    /** Switching a cheat off is not a privilege - anybody, anywhere, may do it. */
    @Test
    void switchingOffNeverNeedsPermission() {
        Cheats.set(Cheats.Mode.HARVEST, true, true);
        assertTrue(Cheats.set(Cheats.Mode.HARVEST, false, false));
        assertFalse(Cheats.isOn(Cheats.Mode.HARVEST));
    }

    /**
     * The switch alone is not enough. A world that stops allowing the mode - the player is deopped,
     * say - takes it away without anything having to write to the switch itself.
     */
    @Test
    void aWithdrawnPermissionSilencesASwitchThatIsStillOn() {
        Cheats.set(Cheats.Mode.MINING, true, true);
        assertFalse(Cheats.isActive(Cheats.Mode.MINING, false));
    }

    /** The grant belonged to the world that gave it, so joining another one starts from nothing. */
    @Test
    void changingWorldsClearsEverything() {
        Cheats.set(Cheats.Mode.MINING, true, true);
        Cheats.set(Cheats.Mode.HARVEST, true, true);
        Cheats.bind(ANOTHER_WORLD);
        assertFalse(Cheats.isOn(Cheats.Mode.MINING));
        assertFalse(Cheats.isOn(Cheats.Mode.HARVEST));
    }

    /** Leaving to the title screen is the same thing: there is no connection left to hold them. */
    @Test
    void leavingTheWorldClearsEverything() {
        Cheats.set(Cheats.Mode.MINING, true, true);
        Cheats.bind(null);
        assertFalse(Cheats.isOn(Cheats.Mode.MINING));
    }

    /** Re-binding the same connection is the per-tick case and must not disturb anything. */
    @Test
    void stayingInTheSameWorldKeepsTheSwitches() {
        Cheats.set(Cheats.Mode.MINING, true, true);
        Cheats.bind(ONE_WORLD);
        Cheats.bind(ONE_WORLD);
        assertTrue(Cheats.isOn(Cheats.Mode.MINING));
    }

    /** Each mode is its own switch; turning one on says nothing about the other. */
    @Test
    void theModesAreIndependent() {
        Cheats.set(Cheats.Mode.MINING, true, true);
        assertFalse(Cheats.isOn(Cheats.Mode.HARVEST));
    }

    /**
     * The command's sub-node names come straight from the enum, so a constant renamed in a way
     * that changes what a player types would otherwise go unnoticed.
     */
    @Test
    void theModesAreNamedAsTheyAreTyped() {
        assertEquals("mining", Cheats.Mode.MINING.id());
        assertEquals("harvest", Cheats.Mode.HARVEST.id());
    }
}
