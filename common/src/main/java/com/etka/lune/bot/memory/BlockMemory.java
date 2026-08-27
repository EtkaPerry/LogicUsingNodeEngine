package com.etka.lune.bot.memory;

import com.etka.lune.bot.BotContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A general seen-block memory. Any task that spots a block can register it here, and later tasks can
 * walk back to a remembered position when the block is no longer in direct line of sight.
 * <p>
 * Positions are only returned while the chunk is loaded and the block is still the same; broken or
 * replaced blocks are forgotten on lookup. Unreachable positions can be blacklisted by the task that
 * failed to reach them.
 */
public final class BlockMemory {

    private static final BlockMemory INSTANCE = new BlockMemory();

    /**
     * How far a remembered block is kept before it is considered "too far away" and dropped. This is
     * larger than the normal vision radius so the bot can remember a find and return to it later.
     */
    private static final int MAX_MEMORY_RADIUS = 512;

    private final Map<Block, Set<Long>> byBlock = new ConcurrentHashMap<>();
    private final Set<Long> unreachable = ConcurrentHashMap.newKeySet();

    private BlockMemory() {}

    public static BlockMemory get() {
        return INSTANCE;
    }

    /** Records that the bot saw this block at this position. */
    public void remember(BlockPos pos, Block block) {
        if (pos == null || block == null) {
            return;
        }
        long packed = pos.asLong();
        byBlock.computeIfAbsent(block, k -> ConcurrentHashMap.newKeySet()).add(packed);
        unreachable.remove(packed);
    }

    /** Removes a position from all block categories. */
    public void forget(BlockPos pos) {
        if (pos == null) {
            return;
        }
        long packed = pos.asLong();
        unreachable.remove(packed);
        for (Set<Long> positions : byBlock.values()) {
            positions.remove(packed);
        }
    }

    /** Marks a position the bot already failed to reach or harvest. */
    public void markUnreachable(BlockPos pos) {
        if (pos == null) {
            return;
        }
        unreachable.add(pos.asLong());
    }

    /**
     * Finds the nearest remembered position for one of the target blocks that is still loaded and
     * still the expected block. Returns null when nothing suitable is remembered.
     */
    public BlockPos findNearest(BotContext ctx, BlockPos centre, Set<Block> targets, int maxRadius) {
        return findNearest(ctx, centre, targets, maxRadius, (pos, state) -> true);
    }

    /**
     * Same as {@link #findNearest}, but only returns positions that also pass an extra state check.
     * This is used for crops that are only useful when mature.
     */
    public BlockPos findNearest(BotContext ctx, BlockPos centre, Set<Block> targets, int maxRadius,
                                java.util.function.BiPredicate<BlockPos, net.minecraft.world.level.block.state.BlockState> predicate) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        long maxSqr = (long) maxRadius * maxRadius;
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;

        for (Block block : targets) {
            Set<Long> positions = byBlock.get(block);
            if (positions == null || positions.isEmpty()) {
                continue;
            }
            for (long packed : positions) {
                BlockPos pos = BlockPos.of(packed);
                if (unreachable.contains(packed)) {
                    continue;
                }
                if (!ctx.level.isLoaded(pos)) {
                    continue;
                }

                double dist = pos.distSqr(centre);
                if (dist > (long) MAX_MEMORY_RADIUS * MAX_MEMORY_RADIUS) {
                    forget(pos);
                    continue;
                }
                net.minecraft.world.level.block.state.BlockState state = ctx.level.getBlockState(pos);
                if (state.getBlock() != block) {
                    forget(pos);
                    continue;
                }
                if (!predicate.test(pos, state)) {
                    continue;
                }
                if (dist <= maxSqr && dist < best) {
                    best = dist;
                    nearest = pos;
                }
            }
        }
        return nearest;
    }

    /** Returns an unmodifiable view of every blacklisted position. */
    public Set<Long> getUnreachable() {
        return Collections.unmodifiableSet(unreachable);
    }

    /** Total number of remembered positions, counting every block category. */
    public int size() {
        int size = 0;
        for (Set<Long> positions : byBlock.values()) {
            size += positions.size();
        }
        return size;
    }
}
