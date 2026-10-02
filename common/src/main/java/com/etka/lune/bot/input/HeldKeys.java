package com.etka.lune.bot.input;

import net.minecraft.client.KeyMapping;

/**
 * Holds one of the game's own keys down, or lets go of it, the same way whatever the player has set.
 *
 * <p>Movement never comes through here - that is {@link BotInput}, read by {@link BotClientInput}.
 * This is for the few keys Lune has to hold on the game's own mappings because vanilla reads them
 * there: use, for the thirty-two ticks a meal takes, and use and attack while the handover keeps
 * the player's own presses away from the game.</p>
 *
 * <p>Both are toggle keys. With Toggle Use or Toggle Attack switched on in the game's controls,
 * {@code setDown(false)} is ignored and {@code setDown(true)} flips the key rather than holding it.
 * Eat used to press use every tick, so under Toggle Use the key went on, off, on, off, and every
 * meal started over without ever finishing; and the stop button's {@code setDown(false)} let go of
 * nothing, leaving a toggled use key on for good. Never call {@code setDown} on a game key directly.
 * </p>
 */
public final class HeldKeys {

    private HeldKeys() {}

    /** Puts the key down or up, and leaves it there, under either setting. */
    public static void set(KeyMapping key, boolean held) {
        if (!held) {
            // Unconditionally, and before asking: a key can read as up while it is still held under
            // a screen that has taken the game's attention, and has to be let go all the same.
            key.setDown(false);
            if (key.isDown()) {
                // A toggled key ignored that, and lets go on a press instead. Only while it still
                // reads as down, or this press would be the one that turned it back on.
                key.setDown(true);
            }
            return;
        }
        // Pressed only when it is up: pressed again, a toggled key would let go.
        if (!key.isDown()) {
            key.setDown(true);
        }
    }
}
