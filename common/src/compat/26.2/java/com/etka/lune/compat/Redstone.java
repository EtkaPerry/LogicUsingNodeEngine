package com.etka.lune.compat;

import net.minecraft.world.level.block.RedStoneWireBlock;

/**
 * Redstone dust, on Minecraft 26.2.
 *
 * <p>26.3 renamed {@code RedStoneWireBlock} to {@code RedstoneWireBlock}; the colour table behind
 * it is the same in every version. This file exists once per folder under {@code common/src/compat};
 * the build compiles the one named by {@code compat_variant} in the target's gradle/versions file.
 * Keep the copies in step.</p>
 */
public final class Redstone {

    private Redstone() {}

    /** The colour dust is tinted at {@code power}, 0 to 15, exactly as the game tints it in the world. */
    public static int dustColour(int power) {
        return RedStoneWireBlock.getColorForPower(Math.clamp(power, 0, 15));
    }
}
