package com.etka.lune.bot.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.ToggleKeyMapping;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game's use and attack keys are toggle keys, and Lune holds them the same way whether the
 * player has Toggle Use switched on or not - against a real {@link ToggleKeyMapping}, not a model
 * of one.
 */
class HeldKeysTest {

    /** The player's Toggle Use, as the key reads it. */
    private static final AtomicBoolean TOGGLE = new AtomicBoolean();
    private static KeyMapping use;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        use = new ToggleKeyMapping("key.lune.test.held_use", InputConstants.KEY_J,
                KeyMapping.Category.GAMEPLAY, TOGGLE::get, false);
    }

    @BeforeEach
    void startUp() {
        TOGGLE.set(false);
        use.setDown(false);
    }

    /** The bug itself, kept so the reason for the helper cannot quietly stop being true. */
    @Test
    void underToggleUseAPressEveryTickFlipsTheKeyAndARelease() {
        TOGGLE.set(true);
        use.setDown(true);
        assertTrue(use.isDown());
        use.setDown(true);
        assertFalse(use.isDown(), "the second press of a meal let go of it");
        use.setDown(true);
        use.setDown(false);
        assertTrue(use.isDown(), "and the stop button's release left it held");
    }

    @Test
    void underToggleUseAKeyHeldEveryTickStaysDown() {
        TOGGLE.set(true);
        for (int tick = 0; tick < 40; tick++) {
            HeldKeys.set(use, true);
            assertTrue(use.isDown(), "a meal held for 32 ticks let go on tick " + tick);
        }
    }

    @Test
    void underToggleUseLettingGoLetsGoAndStaysLetGo() {
        TOGGLE.set(true);
        HeldKeys.set(use, true);
        HeldKeys.set(use, false);
        assertFalse(use.isDown());
        HeldKeys.set(use, false);
        assertFalse(use.isDown(), "letting go of a key that is up must not press it");
    }

    @Test
    void withoutToggleUseItHoldsAndLetsGoAsItAlwaysDid() {
        for (int tick = 0; tick < 3; tick++) {
            HeldKeys.set(use, true);
            assertTrue(use.isDown());
        }
        HeldKeys.set(use, false);
        assertFalse(use.isDown());
        HeldKeys.set(use, false);
        assertFalse(use.isDown());
    }

    /**
     * A key held while the option is switched over under it - in the controls screen, mid-meal - is
     * still let go of.
     */
    @Test
    void aKeyHeldBeforeTheOptionChangedIsStillLetGo() {
        HeldKeys.set(use, true);
        TOGGLE.set(true);
        HeldKeys.set(use, false);
        assertFalse(use.isDown());

        HeldKeys.set(use, true);
        TOGGLE.set(false);
        HeldKeys.set(use, false);
        assertFalse(use.isDown());
    }
}
