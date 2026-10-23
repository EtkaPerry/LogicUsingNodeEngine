package com.etka.lune.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Where the frame to save a screenshot of is, on Minecraft 26.1.2.
 *
 * <p>26.1.2 keeps the main render target on {@code Minecraft.getMainRenderTarget()}; 26.2 moved
 * it onto the game renderer, {@code Minecraft.gameRenderer.mainRenderTarget()}. The
 * {@code Screenshot.grab} overload that takes a file name is the same call in every version, so
 * only the way to the target lives here. This file exists once per folder under
 * {@code common/src/compat}; the build compiles the one named by {@code compat_variant} in the
 * target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Screenshots {

    private Screenshots() {}

    /**
     * Saves the frame on screen as {@code screenshots/<fileName>} under the game directory, the
     * way F2 does, without a line in the chat. Call on the render thread.
     */
    public static void save(Minecraft mc, String fileName) {
        if (mc == null) {
            return;
        }
        Screenshot.grab(mc.gameDirectory, fileName, mc.getMainRenderTarget(), 1, message -> {});
    }
}
