package com.etka.lune.compat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.ItemStack;

/**
 * How long an item burns in a furnace, on Minecraft 26.1.2.
 *
 * <p>up to 26.2 the level carries a fuel table, {@code fuelValues()}, that 26.3 replaced with an item
 * component. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Fuel {

    private Fuel() {}

    /** Ticks {@code stack} keeps a furnace lit, or 0 when it is not fuel. */
    public static int burnDuration(ClientLevel level, ItemStack stack) {
        return level.fuelValues().burnDuration(stack);
    }
}
