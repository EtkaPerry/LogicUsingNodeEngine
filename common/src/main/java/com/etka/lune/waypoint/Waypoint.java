package com.etka.lune.waypoint;

import net.minecraft.core.BlockPos;

/**
 * A named location.
 *
 * @param dimension the dimension id it was saved in, so "go to base" can refuse rather than walk to
 *                  the overworld coordinates while standing in the Nether
 */
public record Waypoint(String name, int x, int y, int z, String dimension) {

    public static Waypoint of(String name, BlockPos pos, String dimension) {
        return new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), dimension);
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    public String describe() {
        return name + "  (" + x + ", " + y + ", " + z + ")";
    }
}
