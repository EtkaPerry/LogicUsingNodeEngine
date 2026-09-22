package com.etka.lune.compat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

/**
 * How long an item burns in a furnace, on Minecraft 26.3.
 *
 * <p>26.3 made fuel an item component ({@code cooking_fuel}) and dropped the level's fuel table. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Fuel {

    private Fuel() {}

    /** Ticks {@code stack} keeps a furnace lit, or 0 when it is not fuel. */
    public static int burnDuration(ClientLevel level, ItemStack stack) {
        CookingFuel fuel = stack.get(DataComponents.COOKING_FUEL);
        if (fuel == null) {
            return 0;
        }
        // Vanilla's fuels carry a plain number. A data pack may point at a context provider instead,
        // which only a server can evaluate; coal's duration stands in so the item still counts as
        // fuel rather than vanishing from the list.
        return fuel.burnTime() instanceof ResolvableInt.Constant constant ? constant.value() : 1600;
    }
}
