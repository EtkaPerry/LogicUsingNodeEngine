package com.etka.lune.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The current screen and how to change it, on Minecraft 26.2.
 *
 * <p>26.1.2 keeps the open screen in {@code Minecraft.screen} and swaps it with
 * {@code Minecraft.setScreen}; 26.2 moved both onto {@code Minecraft.gui}. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Screens {

    private Screens() {}

    /** The screen open right now, or null while the player is looking at the world. */
    public static Screen current(Minecraft mc) {
        return mc.gui.screen();
    }

    /** Opens {@code screen}; null closes whatever is open, exactly as vanilla does. */
    public static void open(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }
}
