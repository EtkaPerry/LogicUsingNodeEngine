package com.etka.lune.waypoint;

import net.minecraft.core.BlockPos;

/**
 * Where the player died, and when.
 *
 * <p>Kept apart from {@link Waypoint} and from {@link Discovery} for the same reason they are kept
 * apart from each other: nobody names a death, and there is nothing to look one up by except being
 * the latest or being the nearest. A waypoint called "Death" would also be a name in the player's
 * language, which is exactly what nothing stored is allowed to be keyed on.</p>
 *
 * <p>Unlike a discovery this has a height, and it matters more than the rest: dying at y=-40 in a
 * ravine and dying on the roof above it are the same column and a very different walk.</p>
 *
 * @param dimension the dimension id, in the form a {@link Waypoint} stores
 * @param diedAt    wall-clock milliseconds, so the list reads as a history and the card can say
 *                  how long the drops have had to despawn
 */
public record Death(String dimension, int x, int y, int z, long diedAt) {

    public static Death at(BlockPos pos, String dimension, long diedAt) {
        return new Death(dimension, pos.getX(), pos.getY(), pos.getZ(), diedAt);
    }

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** Milliseconds since it happened, for "twelve minutes ago" and for despawn arithmetic. */
    public long ageMillis(long now) {
        return Math.max(0L, now - diedAt);
    }
}
