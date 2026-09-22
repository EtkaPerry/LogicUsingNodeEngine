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

    /**
     * How far the bot must move before a written-off position is worth another try, in blocks.
     *
     * <p>A write-off is a fact about a vantage point, not about the block: "I could not get to that
     * log from here". Standing in the same spot and looking at it again is not new information, and
     * treating it as new is what produced a twenty-four tick loop - Explore reports the log, Chop
     * fails to reach it and writes it off, Explore reports it again - that held a bot motionless
     * three blocks from a tree for sixty percent of a nine-thousand-tick run. Moving somewhere else
     * genuinely is new information, because the route that failed is not the route it would take.
     */
    private static final int RETRY_DISTANCE = 5;

    private final Map<Block, Set<Long>> byBlock = new ConcurrentHashMap<>();
    private final Set<Long> unreachable = ConcurrentHashMap.newKeySet();
    /** Where the bot was standing when it wrote each position off. See {@link #RETRY_DISTANCE}. */
    private final Map<Long, Long> writtenOffFrom = new ConcurrentHashMap<>();

    private BlockMemory() {}

    public static BlockMemory get() {
        return INSTANCE;
    }

    /**
     * Records that the bot saw this block at this position.
     *
     * <p>Seeing it does not clear a write-off, and must not. This line used to read
     * {@code unreachable.remove(packed)}, on the reasonable-sounding theory that a block back in
     * view deserves another go. It does not: the bot regularly has a block in view from the exact
     * spot it just failed to reach it from, and Explore calls this for every log it sees. So the
     * write-off Chop Wood had just recorded was erased before the next Chop Wood card could be
     * seeded from it, and the pair span - Explore spots the log, Chop writes it off, Explore spots
     * the log - for sixty percent of a measured run with the bot motionless.
     *
     * <p>What earns a retry is {@link #RETRY_DISTANCE}: having moved.
     */
    public void remember(BlockPos pos, Block block) {
        if (pos == null || block == null) {
            return;
        }
        byBlock.computeIfAbsent(block, k -> ConcurrentHashMap.newKeySet()).add(pos.asLong());
    }

    /** Removes a position from all block categories. */
    public void forget(BlockPos pos) {
        if (pos == null) {
            return;
        }
        long packed = pos.asLong();
        clearWriteOff(packed);
        for (Set<Long> positions : byBlock.values()) {
            positions.remove(packed);
        }
    }

    /**
     * Marks a position the bot already failed to reach or harvest, and where it failed from.
     *
     * <p>{@code from} is the whole point - see {@link #RETRY_DISTANCE}. A caller with no position
     * to offer may pass null, which writes the block off until something explicitly forgets it.
     */
    public void markUnreachable(BlockPos pos, BlockPos from) {
        if (pos == null) {
            return;
        }
        long packed = pos.asLong();
        unreachable.add(packed);
        if (from != null) {
            writtenOffFrom.put(packed, from.asLong());
        }
    }

    /** Whether this position is still written off for a bot standing at {@code from}. */
    public boolean isUnreachableFrom(BlockPos pos, BlockPos from) {
        return pos != null && stillWrittenOff(pos.asLong(), from);
    }

    /**
     * True while the write-off holds; expires and clears it once the bot has moved far enough that
     * the failed route is no longer the route it would take.
     */
    private boolean stillWrittenOff(long packed, BlockPos from) {
        if (!unreachable.contains(packed)) {
            return false;
        }
        Long recorded = writtenOffFrom.get(packed);
        if (recorded == null || from == null) {
            // Written off by a caller that had nowhere to report from. Nothing can expire it but
            // forget(), which is the conservative answer rather than a silent retry loop.
            return true;
        }
        if (BlockPos.of(recorded).distSqr(from) <= (long) RETRY_DISTANCE * RETRY_DISTANCE) {
            return true;
        }
        clearWriteOff(packed);
        return false;
    }

    private void clearWriteOff(long packed) {
        unreachable.remove(packed);
        writtenOffFrom.remove(packed);
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
                // Written off from where the bot is asking now, rather than written off ever: a
                // recall from somewhere new is the retry that RETRY_DISTANCE exists to allow.
                if (stillWrittenOff(packed, centre)) {
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

    /**
     * Every position still written off for a bot standing at {@code from}.
     *
     * <p>This is what seeds a freshly built task's own blacklist, and it is the reason the
     * blacklist survives a rebuild at all - a task that is torn down and recreated every time its
     * card is re-entered cannot remember anything itself. Positions the bot has since walked away
     * from are expired here rather than handed over, so a rebuild in a new place gets a clean look
     * at what it can now reach.
     */
    public Set<Long> getUnreachable(BlockPos from) {
        Set<Long> live = new java.util.HashSet<>();
        for (long packed : unreachable) {
            if (stillWrittenOff(packed, from)) {
                live.add(packed);
            }
        }
        return Collections.unmodifiableSet(live);
    }

    /**
     * How many positions are written off, for the journal.
     *
     * <p>Deliberately not expiry-aware: the journal is reporting what the memory holds, and a count
     * that changed with where the bot happened to be standing would be unreadable as a trend.
     */
    public int unreachableCount() {
        return unreachable.size();
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
