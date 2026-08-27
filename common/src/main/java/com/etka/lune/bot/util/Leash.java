package com.etka.lune.bot.util;

import net.minecraft.core.BlockPos;

/**
 * The area the bot has been told not to leave, shared so that every job respects it at once.
 *
 * <p>A leash is set by the Stay Near monitor and read by anything that searches. That split is the
 * point: the monitor alone would only ever be a yo-yo - it notices the bot has gone too far and
 * walks it back, having already spent the walk out - while a job that knows the boundary simply
 * never chooses work on the far side of it. One is a recovery, the other is not needing one.
 *
 * <p>Distance is horizontal. "Do not leave this area" is a circle drawn on the map, not a sphere:
 * a mine that follows a vein down is still working the same clearing, and a leash that counted
 * depth would drag the bot back up out of its own staircase.
 */
public final class Leash {

    private static final Leash INSTANCE = new Leash();

    private BlockPos anchor;
    private int radius;

    private Leash() {
    }

    public static Leash get() {
        return INSTANCE;
    }

    /** Starts holding an area. A radius of zero or less is the same as no leash at all. */
    public void hold(BlockPos anchor, int radius) {
        if (anchor == null || radius <= 0) {
            release();
            return;
        }
        this.anchor = anchor.immutable();
        this.radius = radius;
    }

    public void release() {
        anchor = null;
        radius = 0;
    }

    public boolean active() {
        return anchor != null && radius > 0;
    }

    public BlockPos anchor() {
        return anchor;
    }

    public int radius() {
        return radius;
    }

    /** Whether a position is inside the held area, or true when nothing is being held. */
    public boolean contains(BlockPos pos) {
        return pos == null || !active() || within(pos.getX() + 0.5, pos.getZ() + 0.5, radius);
    }

    /** Whether a position is inside the area with {@code slack} blocks to spare. */
    public boolean within(double x, double z, double limit) {
        if (!active()) {
            return true;
        }
        double dx = x - (anchor.getX() + 0.5);
        double dz = z - (anchor.getZ() + 0.5);
        return dx * dx + dz * dz <= limit * limit;
    }

    /** Horizontal blocks from the anchor, or zero when nothing is being held. */
    public double distanceFrom(double x, double z) {
        if (!active()) {
            return 0.0;
        }
        double dx = x - (anchor.getX() + 0.5);
        double dz = z - (anchor.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }
}
