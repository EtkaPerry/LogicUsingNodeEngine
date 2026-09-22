package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.memory.BlockMemory;
import com.etka.lune.bot.path.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/**
 * Which block a digging job should stop and mine out of the wall it has just opened.
 *
 * <p>A shaft is only worth cutting because of what it exposes, and the rules for "that one, not
 * that one" are the same wherever the digging happens: it has to be something the job was asked
 * for, standing still, reachable, and safe both to break and to leave a drop under. The staircase
 * had all of that and the corridor had none of it, so a Stripmine walked its branches past the ore
 * it was dug to find - the staircase down collected, and the eight branches that are the actual
 * strip mine collected nothing.</p>
 *
 * <p>The rules, in the order they are cheapest to ask:</p>
 * <ul>
 *   <li><b>Not the route.</b> A position in {@code protectedRoute} is the floor the bot is standing
 *       on or the stair it has to walk back up; ore is never worth breaching it for.</li>
 *   <li><b>Not something else.</b> Anything outside the target set is left alone - but an ore the
 *       bot might want later is written into {@link BlockMemory} on the way past, so a Mine card
 *       can walk back to it.</li>
 *   <li><b>Still there, and breakable.</b> Air, fluids and unbreakable blocks are skipped.</li>
 *   <li><b>Nothing loose above it.</b> Gravel and sand fall into the hole the break leaves.</li>
 *   <li><b>Somewhere for the drop to land.</b> A block overhanging a cave or a lava pool drops its
 *       item where nobody is going to collect it.</li>
 *   <li><b>In sight.</b> {@link Vision} decides, the same as everywhere else, so a job digging with
 *       the cheat off never reaches through a wall for something it cannot see.</li>
 * </ul>
 *
 * <p>Breaking stays with the caller: each one owns a {@link BlockBreaker}, a protected route and its
 * own idea of what a hazard beside the work means - fatal on a staircase that has to be walked back
 * up, merely a block to leave alone in a corridor.</p>
 */
public final class ExposedVein {

    /** How far a drop may fall and still be worth breaking loose. */
    private static final int MAX_DROP = 3;

    private ExposedVein() {}

    /**
     * The first target block worth breaking around a spot the bot is standing on, or null.
     *
     * <p>The box is the ring at foot, head and ceiling height, minus the two blocks the player
     * occupies. Nothing below the feet is offered: that is the floor, and a job that digs its own
     * floor out falls down its own shaft.</p>
     */
    public static BlockPos next(BotContext ctx, BlockPos feet, Set<Block> targets,
                                Set<Long> protectedRoute) {
        return next(ctx, feet, targets, protectedRoute, Set.of());
    }

    /**
     * The same, for a caller that has already tried one and put it aside.
     *
     * <p>{@code skip} is not {@code protectedRoute} and must not be folded into it: a route block
     * is something to break <em>around</em>, and it counts as ground for a drop to land on. A
     * skipped one is a block this job has given up on - a vein with lava behind it, or one that has
     * not broken in twenty seconds - and it says nothing about what is under it.</p>
     */
    public static BlockPos next(BotContext ctx, BlockPos feet, Set<Block> targets,
                                Set<Long> protectedRoute, Set<Long> skip) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) {
                        // The two blocks the player is occupying.
                        continue;
                    }
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (protectedRoute.contains(candidate.asLong()) || skip.contains(candidate.asLong())) {
                        continue;
                    }
                    Block block = ctx.level.getBlockState(candidate).getBlock();
                    if (!targets.contains(block)) {
                        // The dig exposed something else. If it is an ore the bot may need later,
                        // remember the position so a future task can walk back to it.
                        if (BlockCatalog.ores().contains(block) && Vision.isReachable(ctx, candidate)) {
                            BlockMemory.get().remember(candidate, block);
                        }
                        continue;
                    }
                    if (MovementHelper.isPassable(ctx.level, candidate)) {
                        continue;
                    }
                    if (!MovementHelper.isBreakable(ctx.level, candidate)) {
                        continue;
                    }
                    if (MovementHelper.fallingBlocksAbove(ctx.level, candidate) >= 1) {
                        continue;
                    }
                    if (!hasSafeLanding(ctx, candidate, protectedRoute)) {
                        // The block is overhanging an open drop; the item would fall somewhere we
                        // can't collect it, so skip it.
                        continue;
                    }
                    if (!Vision.isReachable(ctx, candidate)) {
                        // Not a clear line of sight yet; leave it for the next block the dig clears.
                        continue;
                    }
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * True when an item breaking off {@code pos} would land on a solid surface within a few blocks,
     * rather than falling down a cave or ravine.
     */
    public static boolean hasSafeLanding(BotContext ctx, BlockPos pos, Set<Long> protectedRoute) {
        for (int i = 1; i <= MAX_DROP; i++) {
            BlockPos below = pos.below(i);
            if (protectedRoute.contains(below.asLong())) {
                return true;
            }
            if (MovementHelper.isSolidFloor(ctx.level, below)) {
                return true;
            }
            if (!MovementHelper.isPassable(ctx.level, below)) {
                // A non-passable, non-floor block (e.g. lava) is not safe to drop onto.
                return false;
            }
        }
        return false;
    }
}
