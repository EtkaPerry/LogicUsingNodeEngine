package com.etka.lune.compat;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The dyed blocks Lune looks for, on Minecraft 26.3.
 *
 * <p>26.2 folded the sixteen colours of a bed or banner into one {@code ColorCollection} constant
 * ({@code Blocks.BED.white()}) where 26.1.2 had {@code Blocks.WHITE_BED}. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class DyedBlocks {

    public static final Block WHITE_BED = Blocks.BED.white();
    public static final Block WHITE_BANNER = Blocks.BANNER.white();
    public static final Block WHITE_WALL_BANNER = Blocks.WALL_BANNER.white();

    private DyedBlocks() {}
}
