package com.etka.lune.waypoint.external;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A place another mod knows about, read into Lune's own shape.
 *
 * @param source    the {@link ExternalWaypointSource#id()} it was read from
 * @param name      what that mod calls it, as the player sees it there
 * @param dimension the dimension id, in the form {@link com.etka.lune.waypoint.Waypoint} stores
 * @param hasY      false when the other mod only recorded a column. Xaero's Minimap lets a
 *                  waypoint leave its height out, and then {@code y} is nothing but a guess
 */
public record ExternalWaypoint(String source, String name, int x, int y, int z, String dimension,
                               boolean hasY) {

    public BlockPos pos() {
        return new BlockPos(x, y, z);
    }

    /** Distance from {@code from} to the block's centre; horizontal only when the height is unknown. */
    public double distanceFrom(Vec3 from) {
        double dx = x + 0.5 - from.x;
        double dz = z + 0.5 - from.z;
        double dy = hasY ? y + 0.5 - from.y : 0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public boolean isIn(String dimension) {
        return this.dimension.equals(dimension);
    }
}
