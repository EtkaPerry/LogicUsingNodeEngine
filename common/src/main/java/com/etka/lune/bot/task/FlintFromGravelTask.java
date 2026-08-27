package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.InventoryHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * Turns carried gravel into flint by putting it down and breaking it again.
 *
 * <p>Gravel yields flint about one time in ten, so a bank is not a source of flint so much as a
 * source of rolls. Carrying gravel away from a bank passed earlier only helps if those rolls can be
 * taken later, which needs the block on the ground: an item in the inventory cannot be broken.
 * Placing one block and breaking it is the whole loop, and it is exactly what a player does while
 * standing in the same spot rather than walking to find another bank.
 *
 * <p>Bounded twice over - by the gravel carried and by a tick budget - because a run of bad luck is
 * a real possibility at one in ten and must not become the phase.
 */
public class FlintFromGravelTask implements Task {

    /** Ticks before the loop gives up, so bad luck cannot consume the phase. */
    private static final int MAX_TICKS = 600;
    /** Ticks to wait after a break for the drop to be picked up before placing the next block. */
    private static final int COLLECT_TICKS = 20;
    /** Unusable spots tolerated before accepting that this is not somewhere to knap gravel. */
    private static final int MAX_BAD_SITES = 6;
    /** Blocks either side of the player to consider putting a block down on. */
    private static final int SITE_SEARCH = 2;

    private final BlockBreaker breaker = new BlockBreaker();
    private final Set<Long> rejected = new HashSet<>();
    private BlockPos site;
    private int ticks;
    private int collecting;
    private int badSites;
    private String status = "";

    @Override
    public String name() {
        return "Flint From Gravel";
    }

    @Override
    public void onStart(BotContext ctx) {
        site = null;
        ticks = 0;
        collecting = 0;
        badSites = 0;
        rejected.clear();
        status = "knapping gravel for flint";
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (InventoryHelper.has(ctx.player, Items.FLINT, 1)) {
            status = "got the flint";
            return TaskStatus.SUCCESS;
        }
        if (++ticks > MAX_TICKS) {
            status = "no flint after " + ticks + " ticks of knapping";
            return TaskStatus.FAILED;
        }
        if (!InventoryHelper.has(ctx.player, Items.GRAVEL, 1)) {
            status = "out of gravel without a flint";
            return TaskStatus.FAILED;
        }
        // Let the drop come to the player before the spot is reused; placing straight over an item
        // that has not been picked up yet loses the gravel it was meant to recycle.
        if (collecting > 0) {
            collecting--;
            status = "collecting the gravel back";
            return TaskStatus.RUNNING;
        }

        if (site == null) {
            site = placementSite(ctx);
            if (site == null) {
                status = "nowhere to set a block down here";
                return TaskStatus.FAILED;
            }
        }

        if (!ctx.level.getBlockState(site).is(Blocks.GRAVEL)) {
            // React to *why* a placement did not happen. Treating every failure as "keep trying"
            // spent this task's whole budget clicking at one unusable spot inside a shipwreck: 599
            // ticks of "placing gravel" with four gravel still in the bag. Only a placement that is
            // genuinely mid-interaction is worth repeating; a spot that is blocked or unsupported
            // will be just as blocked next tick, so give up on the spot rather than on the tick.
            BlockPlacer.PlacementResult result = BlockPlacer.tryPlace(ctx, Blocks.GRAVEL, site);
            switch (result) {
                case PLACED, ALREADY_PRESENT -> { }
                case NO_MATERIAL -> {
                    status = "out of gravel without a flint";
                    return TaskStatus.FAILED;
                }
                case BLOCKED, NO_SUPPORT, OUT_OF_REACH -> {
                    rejected.add(site.asLong());
                    site = null;
                    if (++badSites >= MAX_BAD_SITES) {
                        status = "nowhere to set a block down here";
                        return TaskStatus.FAILED;
                    }
                    status = "looking for somewhere to set the gravel down";
                    return TaskStatus.RUNNING;
                }
                default -> {
                    status = "placing gravel";
                    return TaskStatus.RUNNING;
                }
            }
        }

        BlockBreaker.Progress progress = breaker.tick(ctx, site);
        if (progress == BlockBreaker.Progress.FINISHED) {
            collecting = COLLECT_TICKS;
            ctx.debug.decide("broke a placed gravel block; hoping for flint");
        } else if (progress == BlockBreaker.Progress.NO_TOOL
                || progress == BlockBreaker.Progress.HAZARD) {
            status = "cannot break the gravel here";
            return TaskStatus.FAILED;
        }
        status = "knapping gravel for flint";
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(BotContext ctx) {
        breaker.stop(ctx);
        ctx.input.reset();
    }

    @Override
    public String status() {
        return status;
    }

    /**
     * A block beside the player with solid ground under it. Gravel falls, so putting it anywhere
     * unsupported drops it out of reach; beside rather than under keeps the player off it.
     */
    private BlockPos placementSite(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        // Everything within arm's length, not just the four blocks touching the feet. The narrow
        // version failed three ticks into the phase while standing on a wreck deck and took the
        // whole knapping idea down with it, gravel still in the bag.
        for (int dx = -SITE_SEARCH; dx <= SITE_SEARCH; dx++) {
            for (int dz = -SITE_SEARCH; dz <= SITE_SEARCH; dz++) {
                for (int dy = SITE_SEARCH; dy >= -SITE_SEARCH; dy--) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (rejected.contains(candidate.asLong())
                            || candidate.equals(feet) || candidate.equals(feet.above())) {
                        continue;
                    }
                    double distance = feet.distSqr(candidate);
                    if (distance >= bestDistance
                            || !ctx.level.isLoaded(candidate)
                            || !MovementHelper.isPassable(ctx.level, candidate)
                            || !MovementHelper.isSolidFloor(ctx.level, candidate.below())
                            || MovementHelper.isLiquid(ctx.level, candidate)
                            || MovementHelper.isLiquid(ctx.level, candidate.below())) {
                        continue;
                    }
                    // Gravel falls, so anything out of reach when it lands is gravel thrown away.
                    if (ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(candidate))
                            > BlockBreaker.REACH * BlockBreaker.REACH) {
                        continue;
                    }
                    bestDistance = distance;
                    best = candidate.immutable();
                }
            }
        }
        return best;
    }
}
