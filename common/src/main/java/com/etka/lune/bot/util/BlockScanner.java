package com.etka.lune.bot.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Finds the nearest block of interest around the player.
 * <p>
 * Scans outward one cube shell at a time rather than sweeping a whole box, so the common case - an
 * ore a few blocks away - costs a few hundred lookups instead of the hundreds of thousands a
 * radius-32 box scan would need. Only the surface of each shell is visited, so no position is
 * checked twice.
 */
public final class BlockScanner {

    private BlockScanner() {}

    /**
     * @param yMin/yMax inclusive vertical clamp, so Mine can be told to ignore everything above the
     *                  ore band it cares about
     * @param excluded  packed positions to skip, letting a caller blacklist blocks it already
     *                  failed to reach instead of retargeting the same one forever
     * @return the closest matching position, or null if nothing matched within {@code maxRadius}
     */
    public static BlockPos findNearest(BlockGetter level, BlockPos centre, Set<Block> targets,
                                       int maxRadius, int yMin, int yMax, Set<Long> excluded) {
        return findNearest(level, centre, targets, maxRadius, yMin, yMax, excluded, (pos, state) -> true);
    }

    /** As above, with nothing blacklisted. */
    public static BlockPos findNearest(BlockGetter level, BlockPos centre, Set<Block> targets,
                                       int maxRadius, int yMin, int yMax) {
        return findNearest(level, centre, targets, maxRadius, yMin, yMax, Set.of());
    }

    /**
     * Same as the other overloads, but only returns positions where {@code filter} is satisfied.
     * This is used for human-like vision checks: the block must be visible before the bot acts.
     */
    public static BlockPos findNearest(BlockGetter level, BlockPos centre, Set<Block> targets,
                                       int maxRadius, int yMin, int yMax, Set<Long> excluded,
                                       BiPredicate<BlockPos, BlockState> filter) {
        return findNearestInShells(level, centre, targets, 0, maxRadius, yMin, yMax, excluded, filter);
    }

    /**
     * Searches only a consecutive band of cube shells. Long-running tasks use this to spread a
     * large visible-block search over multiple client ticks instead of freezing movement while a
     * whole radius-64 cube is inspected in one frame.
     */
    public static BlockPos findNearestInShells(BlockGetter level, BlockPos centre, Set<Block> targets,
                                               int firstRadius, int lastRadius, int yMin, int yMax,
                                               Set<Long> excluded,
                                               BiPredicate<BlockPos, BlockState> filter) {
        if (targets.isEmpty()) {
            return null;
        }
        int first = Math.max(0, firstRadius);
        int last = Math.max(first, lastRadius);
        for (int radius = first; radius <= last; radius++) {
            BlockPos found = scanShell(level, centre, targets, radius, yMin, yMax, excluded, filter);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Checks every position at exactly Chebyshev distance {@code radius}, returning the closest hit. */
    private static BlockPos scanShell(BlockGetter level, BlockPos centre, Set<Block> targets,
                                      int radius, int yMin, int yMax, Set<Long> excluded,
                                      BiPredicate<BlockPos, BlockState> filter) {
        if (radius == 0) {
            return matches(level, centre, targets, excluded, filter) ? centre : null;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        // Top and bottom caps: full squares.
        for (int dy = -radius; dy <= radius; dy += 2 * radius) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos hit = check(level, centre, cursor, targets, dx, dy, dz, yMin, yMax, excluded, filter);
                    if (hit != null) {
                        double distance = hit.distSqr(centre);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = hit;
                        }
                    }
                }
            }
        }

        // Sides: the ring at each intermediate height, without re-visiting the caps.
        for (int dy = -radius + 1; dy <= radius - 1; dy++) {
            for (int dx = -radius; dx <= radius; dx += 2 * radius) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos hit = check(level, centre, cursor, targets, dx, dy, dz, yMin, yMax, excluded, filter);
                    if (hit != null) {
                        double distance = hit.distSqr(centre);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = hit;
                        }
                    }
                }
            }
            for (int dz = -radius; dz <= radius; dz += 2 * radius) {
                for (int dx = -radius + 1; dx <= radius - 1; dx++) {
                    BlockPos hit = check(level, centre, cursor, targets, dx, dy, dz, yMin, yMax, excluded, filter);
                    if (hit != null) {
                        double distance = hit.distSqr(centre);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = hit;
                        }
                    }
                }
            }
        }

        return best;
    }

    private static BlockPos check(BlockGetter level, BlockPos centre, BlockPos.MutableBlockPos cursor,
                                  Set<Block> targets, int dx, int dy, int dz, int yMin, int yMax,
                                  Set<Long> excluded, BiPredicate<BlockPos, BlockState> filter) {
        int y = centre.getY() + dy;
        if (y < yMin || y > yMax) {
            return null;
        }
        cursor.set(centre.getX() + dx, y, centre.getZ() + dz);
        return matches(level, cursor, targets, excluded, filter) ? cursor.immutable() : null;
    }

    private static boolean matches(BlockGetter level, BlockPos pos, Set<Block> targets, Set<Long> excluded,
                                   BiPredicate<BlockPos, BlockState> filter) {
        if (excluded.contains(pos.asLong())) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return targets.contains(state.getBlock()) && filter.test(pos, state);
    }
}
