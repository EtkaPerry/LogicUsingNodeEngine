package com.etka.lune.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Where a toast goes, on Minecraft 26.1.2.
 *
 * <p>26.1.2 keeps the toast queue on {@code Minecraft.getToastManager()}; 26.2 moved it onto
 * {@code Minecraft.gui}, beside the screen. {@code SystemToast.addOrUpdate} itself is the same
 * call in every version, so only the way to the manager lives here. This file exists once per
 * folder under {@code common/src/compat}; the build compiles the one named by
 * {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Toasts {

    private Toasts() {}

    /**
     * Shows {@code title} and {@code message} as a toast, replacing whatever is already on screen
     * under {@code id} rather than stacking behind it.
     */
    public static void show(Minecraft mc, SystemToast.SystemToastId id, Component title, Component message) {
        if (mc == null || mc.getToastManager() == null) {
            return;
        }
        SystemToast.addOrUpdate(mc.getToastManager(), id, title, message);
    }
}
