package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.memory.CraftingTableMemory;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Picks up a nearby crafting table the bot previously placed, so it doesn't leave a trail of tables
 * behind on short trips. It only goes after tables inside a small radius; a table left far away is
 * left for the memory system to re-use later.
 */
public final class CollectCraftingTableTask implements Task {

    private static final int RADIUS = 8;
    /** A table takes about 75 ticks by hand; allow time for aiming and server latency as well. */
    private static final int BREAK_TIMEOUT = 200;
    private static final int WALK_TIMEOUT = 240;
    private static final int PICKUP_TIMEOUT = 240;
    /** Allow a delayed item spawn a short grace period, but never wait forever for it. */
    private static final int DROP_SPAWN_GRACE_TICKS = 20;

    private BlockPos target;
    private GotoTask approach;
    private final BlockBreaker breaker = new BlockBreaker();
    private LootTask pickup;
    private int phaseTicks;
    private int dropWaitTicks;
    private boolean collecting;
    private String status = "";

    @Override
    public String name() {
        return "Collect table";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)) {
            status = "has the table";
            return TaskStatus.SUCCESS;
        }

        if (target == null) {
            target = CraftingTableMemory.get().findNearest(ctx, ctx.player.blockPosition(), RADIUS);
            if (target == null) {
                status = "no table nearby";
                return TaskStatus.SUCCESS;
            }
            phaseTicks = 0;
        }

        if (!ctx.level.getBlockState(target).is(Blocks.CRAFTING_TABLE)) {
            return collectDrop(ctx);
        }

        double reachSqr = 20.0;
        boolean inReach = ctx.player.getEyePosition().distanceToSqr(
                net.minecraft.world.phys.Vec3.atCenterOf(target)) <= reachSqr;

        if (inReach) {
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
                phaseTicks = 0;
            }
            if (++phaseTicks > BREAK_TIMEOUT) {
                breaker.stop(ctx);
                status = "couldn't finish breaking the table";
                return TaskStatus.FAILED;
            }
            BlockBreaker.Progress progress = breaker.tick(ctx, target);
            if (progress == BlockBreaker.Progress.FINISHED) {
                return collectDrop(ctx);
            }
            if (progress == BlockBreaker.Progress.NO_TOOL) {
                status = "no tool to break the table";
                return TaskStatus.FAILED;
            }
            if (progress == BlockBreaker.Progress.HAZARD) {
                status = breaker.getFailureReason();
                return TaskStatus.FAILED;
            }
            status = "breaking the table";
            return TaskStatus.RUNNING;
        }

        if (++phaseTicks > WALK_TIMEOUT) {
            CraftingTableMemory.get().markUnreachable(target);
            status = "timed out walking to the table";
            return TaskStatus.FAILED;
        }

        if (approach == null) {
            approach = new GotoTask(new Goals.Adjacent(target, 4.0), false, false);
            approach.start(ctx);
        }
        TaskStatus walk = approach.tick(ctx);
        if (walk == TaskStatus.FAILED) {
            CraftingTableMemory.get().markUnreachable(target);
            approach.stop(ctx);
            approach = null;
            status = "couldn't reach the table";
            return TaskStatus.FAILED;
        }
        status = "walking to the table";
        return TaskStatus.RUNNING;
    }

    /**
     * A broken block is not collected automatically from interaction range. Walk onto its item
     * entity, retrying while the server is still creating the drop, and only finish once the table
     * is actually back in the inventory.
     */
    private TaskStatus collectDrop(BotContext ctx) {
        CraftingTableMemory.get().forget(target);
        breaker.stop(ctx);

        if (!collecting) {
            // Give pickup its own deadline; time spent approaching and breaking must not consume it.
            collecting = true;
            phaseTicks = 0;
            dropWaitTicks = 0;
        }
        if (++phaseTicks > PICKUP_TIMEOUT) {
            status = "table broke, but its drop couldn't be collected";
            return TaskStatus.FAILED;
        }

        if (pickup == null) {
            pickup = new LootTask(RADIUS);
            pickup.start(ctx);
        }
        TaskStatus result = pickup.tick(ctx);
        if (InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)) {
            status = "collected the table";
            return TaskStatus.SUCCESS;
        }
        if (result != TaskStatus.RUNNING) {
            if (pickup.unreachableCount() > 0) {
                pickup.stop(ctx);
                pickup = null;
                status = "table drop is unreachable";
                return TaskStatus.FAILED;
            }

            // The entity can appear a tick or two after the block update. Start a fresh bounded
            // sweep next tick instead of treating one empty scan as completion. Once that grace
            // period expires, report failure so the parent task can recover instead of spinning.
            pickup.stop(ctx);
            pickup = null;
            if (++dropWaitTicks <= DROP_SPAWN_GRACE_TICKS) {
                status = "waiting for the table drop";
            } else {
                status = "table broke, but its drop did not appear";
                return TaskStatus.FAILED;
            }
        } else {
            dropWaitTicks = 0;
            status = "collecting the table - " + pickup.status();
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (pickup != null) {
            pickup.stop(ctx);
            pickup = null;
        }
        breaker.stop(ctx);
        ctx.input.reset();
    }
}
