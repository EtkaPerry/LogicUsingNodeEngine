package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.bot.path.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * Mines a small, safe amount of cobblestone for early tool upgrades.
 * <p>
 * It digs a short staircase down, checking the floor of each step so it never falls into a cave,
 * and mines any stone in the side walls of the stair. After every stone it breaks it sweeps up the
 * drop before moving on, so cobble does not get left behind in the shaft.
 */
public final class QuickStoneTask implements Task {

    private static final Set<Block> STONE_BLOCKS = Set.of(Blocks.STONE);
    private static final int MAX_STEPS = 16;
    /**
     * Deliberately tight. Cobblestone broken in a stairwell lands at the bot's feet, so a wider
     * sweep buys nothing and costs a great deal: chasing a stray drop up the shaft leaves the bot
     * standing on the surface with a half-dug staircase below it, which is not a position it
     * recovers from gracefully. Anything further away is picked up on the way past later.
     */
    private static final int COLLECT_RADIUS = 3;
    /** How far around the finished shaft to keep mining when the staircase cannot go further. */
    private static final int WALL_MINE_RADIUS = 8;
    /** A depleted staircase must move to a new surface pocket before trying the same prospect again. */
    private static final int MAX_RELOCATIONS = 3;
    /** Visible stone blocks that make mining what is here better than digging somewhere new. */
    private static final int MIN_EXPOSED_STONE = 4;

    private final int needed;
    private final int directionOffset;
    /** Failed starts shared with EnsureToolTask so a rebuilt gatherer must choose a new pocket. */
    private final Set<Long> excludedStarts;
    private StaircaseProspectTask digger;
    private MineTask miner;
    private LootTask sweeper;
    /** The safe surface/staging block where this short mining trip began. */
    private BlockPos returnPosition;
    private GotoTask returnTask;
    /** A tree canopy or shallow ledge is not a safe place to start a descending stair. */
    private GotoTask stagingTask;
    private BlockPos stagingPosition;
    private GotoTask relocationTask;
    private final Set<Long> attemptedStarts = new HashSet<>();
    private int relocationAttempts;
    private int gathered;
    private boolean progressed;
    /** The staircase has ended, so it must not be ticked again expecting further progress. */
    private boolean diggerDone;
    /** Why it ended, so a failure here reports the cause and not just a cobblestone count. */
    private String diggerFailure = "";
    private String status = "";

    private enum Phase { DIG, SWEEP, MINE }

    private Phase phase = Phase.DIG;

    public QuickStoneTask(int needed) {
        this(needed, 0);
    }

    public QuickStoneTask(int needed, int directionOffset) {
        this(needed, directionOffset, null);
    }

    public QuickStoneTask(int needed, int directionOffset, Set<Long> excludedStarts) {
        this.needed = Math.max(1, needed);
        this.directionOffset = Math.floorMod(directionOffset, 4);
        this.excludedStarts = excludedStarts;
    }

    @Override
    public String name() {
        return "Mine " + needed + " stone";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskProgress learningProgress() {
        return new TaskProgress(gathered, needed, "stone");
    }

    @Override
    public boolean madeProgress() {
        // A staircase can break useful stone and then fail while collecting it. Let the parent
        // dependency resolver re-check the inventory and decide whether another short attempt is
        // worthwhile instead of discarding that partial work.
        return progressed || gathered > 0;
    }

    @Override
    public void onStart(BotContext ctx) {
        returnPosition = ctx.player.blockPosition();
        attemptedStarts.clear();
        attemptedStarts.add(returnPosition.asLong());
        if (excludedStarts != null) {
            excludedStarts.add(returnPosition.asLong());
            attemptedStarts.addAll(excludedStarts);
        }
        relocationAttempts = 0;
        returnTask = null;
        stagingTask = null;
        stagingPosition = null;
        relocationTask = null;
        // Sand and gravel are valid footing, but they are poor mining starts: a bounded stair can
        // spend its whole budget in a shoreline shelf before it ever reaches a visible stone face.
        // Prefer a nearby solid ground position, while retaining the loose-surface fallback when
        // the player is genuinely surrounded by beach.
        if (MovementHelper.isStableMiningStart(ctx.level, returnPosition)
                && !MovementHelper.isLooseMiningSurface(ctx.level, returnPosition)) {
            beginDig(ctx);
        } else if ((stagingPosition = MovementHelper.findStableMiningStart(ctx.level,
                returnPosition, 24, -32, 2, attemptedStarts, true)) != null) {
            // The staging coordinate is not a vague travel destination: it is the exact feet
            // position whose floor we inspected. A Near goal can report success one or two blocks
            // away, putting the bot straight back on a canopy and making the following stair
            // prospect fail in the same place.
            stagingTask = new GotoTask(new Goals.Block(stagingPosition), true, true);
            if (excludedStarts != null) {
                excludedStarts.add(stagingPosition.asLong());
            }
            stagingTask.start(ctx);
            status = "leaving an unstable start for solid ground";
        } else {
            status = "no stable ground nearby for the stone stair";
        }
        gathered = 0;
        progressed = false;
        diggerDone = false;
        diggerFailure = "";
        sweeper = null;
        miner = null;
        phase = Phase.DIG;

        // Standing in a shaft dug a moment ago, with its walls full of exposed stone, the last
        // thing to do is sink another one beside it. Each stone task used to start at DIG
        // regardless, so a run that needed fourteen cobble dug a staircase, took the four it
        // happened to pass, came back up, and started again - in the same hole.
        if (stagingTask == null && exposedStoneNearby(ctx)) {
            phase = Phase.MINE;
            status = "stone is already exposed here; mining it before digging further";
        }
    }

    /**
     * Whether there is enough visible stone within reach to be worth mining instead of digging.
     * Visible, because stone sealed behind the wall of the shaft is not exposed - it is the reason
     * the shaft would be extended.
     */
    private boolean exposedStoneNearby(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        int found = 0;
        for (int dx = -WALL_MINE_RADIUS; dx <= WALL_MINE_RADIUS; dx++) {
            for (int dz = -WALL_MINE_RADIUS; dz <= WALL_MINE_RADIUS; dz++) {
                for (int dy = -WALL_MINE_RADIUS; dy <= 2; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!ctx.level.isLoaded(candidate)
                            || !STONE_BLOCKS.contains(ctx.level.getBlockState(candidate).getBlock())) {
                        continue;
                    }
                    if (Vision.isVisible(ctx, candidate) && ++found >= MIN_EXPOSED_STONE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (stagingTask != null) {
            TaskStatus result = stagingTask.tick(ctx);
            status = "moving to a stable stone start - " + stagingTask.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stagingTask.stop(ctx);
            stagingTask = null;
            if (result == TaskStatus.FAILED) {
                status = "could not reach stable ground for the stone stair";
                return TaskStatus.FAILED;
            }
            returnPosition = ctx.player.blockPosition();
            attemptedStarts.add(returnPosition.asLong());
            beginDig(ctx);
            status = "starting the stone stair from solid ground";
            return TaskStatus.RUNNING;
        }
        if (relocationTask != null) {
            TaskStatus result = relocationTask.tick(ctx);
            status = "moving to another stone start - " + relocationTask.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            relocationTask.stop(ctx);
            relocationTask = null;
            if (result == TaskStatus.FAILED) {
                status = "could not reach another stone start";
                return checkDone(ctx);
            }
            returnPosition = ctx.player.blockPosition();
            attemptedStarts.add(returnPosition.asLong());
            diggerDone = false;
            diggerFailure = "";
            phase = Phase.DIG;
            beginDig(ctx);
            status = "trying a fresh stone stair from solid ground";
            return TaskStatus.RUNNING;
        }
        if (returnTask != null) {
            TaskStatus result = returnTask.tick(ctx);
            status = "returning to the stone start - " + returnTask.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            returnTask.stop(ctx);
            returnTask = null;
            if (result == TaskStatus.FAILED) {
                status = "could not return to the stone start";
                return TaskStatus.FAILED;
            }
            if (hasEnough(ctx)) {
                status = "returned to the stone start";
                progressed = true;
                return TaskStatus.SUCCESS;
            }
            status = "returned to the stone start with only "
                    + InventoryHelper.count(ctx.player, Items.COBBLESTONE) + "/" + needed
                    + " cobblestone";
            return TaskStatus.FAILED;
        }
        return switch (phase) {
            case DIG -> tickDig(ctx);
            case SWEEP -> tickSweep(ctx);
            case MINE -> tickMine(ctx);
        };
    }

    private TaskStatus tickDig(BotContext ctx) {
        if (digger == null) {
            status = "no stable ground nearby for the stone stair";
            return TaskStatus.FAILED;
        }

        TaskStatus result = digger.tick(ctx);
        status = digger.status();
        int newlyBroken = digger.drainMatchingBlocksBroken();
        gathered += newlyBroken;

        if (result != TaskStatus.RUNNING) {
            // Remember that the staircase is finished with, whichever way it ended. tickSweep only
            // ever sees the *sweeper's* result, so without this a digger that failed - on a block
            // it has no tool for, say - is resumed on the next tick and fails in exactly the same
            // spot, forever, with nothing above it ever learning that anything went wrong.
            diggerDone = true;
            if (result == TaskStatus.FAILED) {
                diggerFailure = digger.status();
            }
        }

        if (newlyBroken > 0 || result != TaskStatus.RUNNING) {
            // Collect what we just broke before walking any further. If the digger finished or
            // failed we still sweep, then decide if we have enough.
            digger.onPause(ctx);
            phase = Phase.SWEEP;
            startSweep(ctx);
            return tickSweep(ctx);
        }

        return TaskStatus.RUNNING;
    }

    private TaskStatus tickSweep(BotContext ctx) {
        if (sweeper == null) {
            return checkDone(ctx);
        }

        TaskStatus result = sweeper.tick(ctx);
        if (result == TaskStatus.RUNNING) {
            status = sweeper.status();
            return TaskStatus.RUNNING;
        }

        sweeper.stop(ctx);
        sweeper = null;

        if (hasEnough(ctx)) {
            return checkDone(ctx);
        }

        if (result == TaskStatus.FAILED || digger == null || diggerDone) {
            // The staircase has stopped - a cave underneath, water behind the next block, whatever.
            // The bot is nevertheless standing in a shaft with stone on every side. Mining that out
            // is the obvious thing to do and it is what a player would do; walking back up to start
            // a fresh staircase somewhere else throws away the hole that was just dug.
            if (diggerDone && !hasEnough(ctx)) {
                // Water and lava are the exception: the staircase deliberately stopped before the
                // player entered the hazard. Mining the surrounding walls would discard that safety
                // decision and can strand the player in the same flooded pocket.
                if (diggerFailure.contains("water") || diggerFailure.contains("lava")) {
                    return checkDone(ctx);
                }
                startMining(ctx);
                phase = Phase.MINE;
                return TaskStatus.RUNNING;
            }
            return checkDone(ctx);
        }

        // More stone still needed and the digger can keep going.
        phase = Phase.DIG;
        status = digger.status();
        return TaskStatus.RUNNING;
    }

    /** Works the stone already exposed around the shaft instead of abandoning it. */
    private void startMining(BotContext ctx) {
        int missing = Math.max(1, needed - InventoryHelper.count(ctx.player, Items.COBBLESTONE));
        int y = ctx.player.blockPosition().getY();
        // The stair can stop beside a cave or behind a dirt/grass lip without exposing a stone
        // face from the current view. Keep the wall pass vision-safe, but let it open a bounded
        // prospecting branch instead of treating that occluded stone as "nothing left".
        miner = new MineTask(STONE_BLOCKS, WALL_MINE_RADIUS, y - WALL_MINE_RADIUS, y + 2,
                missing, false, true, false, true);
        miner.start(ctx);
    }

    private TaskStatus tickMine(BotContext ctx) {
        if (miner == null) {
            return checkDone(ctx);
        }
        TaskStatus result = miner.tick(ctx);
        status = "mining out the shaft - " + miner.status();
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        miner.stop(ctx);
        miner = null;
        if (!hasEnough(ctx) && startRelocation(ctx)) {
            return TaskStatus.RUNNING;
        }
        return checkDone(ctx);
    }

    /**
     * Move the bounded stone search to another physical start after a local pocket is exhausted.
     * Rebuilding MineTask at the same feet position only rotates its stair and is deterministic in
     * a beach shelf, so it cannot discover the solid ground/cave that a player would walk toward.
     */
    private boolean startRelocation(BotContext ctx) {
        if (relocationAttempts >= MAX_RELOCATIONS) {
            return false;
        }
        BlockPos next = MovementHelper.findStableMiningStart(ctx.level,
                ctx.player.blockPosition(), 24, -32, 2, attemptedStarts, true);
        if (next == null || attemptedStarts.contains(next.asLong())) {
            return false;
        }
        relocationAttempts++;
        relocationTask = new GotoTask(new Goals.Block(next), true, false);
        relocationTask.start(ctx);
        status = "local stone pocket exhausted; relocating to another solid start";
        return true;
    }

    private boolean hasEnough(BotContext ctx) {
        return InventoryHelper.count(ctx.player, Items.COBBLESTONE) >= needed;
    }

    private TaskStatus checkDone(BotContext ctx) {
        int cobble = InventoryHelper.count(ctx.player, Items.COBBLESTONE);
        if (cobble >= needed) {
            if (returnTask == null && returnPosition != null
                    && !new Goals.Near(returnPosition, 2).isReached(ctx.player.blockPosition())) {
                returnTask = new GotoTask(new Goals.Near(returnPosition, 2), true, false);
                returnTask.start(ctx);
                status = "returning to the stone start";
                return TaskStatus.RUNNING;
            }
            status = "mined " + cobble + " cobblestone";
            progressed = true;
            return TaskStatus.SUCCESS;
        }
        status = "only collected " + cobble + "/" + needed + " cobblestone"
                + (diggerFailure.isEmpty() ? "" : " - " + diggerFailure);
        // A failed gathering attempt must hand the caller back a safe, reusable position. Without
        // this, a retry starts at the bottom of the old shaft, rotates its direction from there,
        // and digs a second hole before it has ever had a chance to try another surface route.
        if (returnTask == null && returnPosition != null
                && !new Goals.Near(returnPosition, 2).isReached(ctx.player.blockPosition())) {
            returnTask = new GotoTask(new Goals.Near(returnPosition, 2), true, false);
            returnTask.start(ctx);
            status = "returning to the stone start before retrying - " + cobble + "/" + needed;
            return TaskStatus.RUNNING;
        }
        return TaskStatus.FAILED;
    }

    private void startSweep(BotContext ctx) {
        // This detour exists to collect the cobblestone from the staircase. Do not let old tree
        // drops near the surface turn it into a general Loot command before the stone phase can
        // finish; the next food phase has its own source policy for edible items.
        sweeper = new LootTask(COLLECT_RADIUS, 80,
                stack -> stack.is(Items.COBBLESTONE));
        sweeper.start(ctx);
    }

    private void beginDig(BotContext ctx) {
        Direction facing = rotated(ctx.player.getDirection(), directionOffset);
        digger = new StaircaseProspectTask(facing, Math.max(needed, MAX_STEPS), STONE_BLOCKS);
        digger.start(ctx);
    }

    private static Direction rotated(Direction facing, int offset) {
        return switch (Math.floorMod(offset, 4)) {
            case 1 -> facing.getClockWise();
            case 2 -> facing.getOpposite();
            case 3 -> facing.getCounterClockWise();
            default -> facing;
        };
    }

    @Override
    public void onPause(BotContext ctx) {
        if (digger != null) {
            digger.onPause(ctx);
        }
        if (sweeper != null) {
            sweeper.onPause(ctx);
        }
        if (returnTask != null) {
            returnTask.onPause(ctx);
        }
        if (relocationTask != null) {
            relocationTask.onPause(ctx);
        }
        if (stagingTask != null) {
            stagingTask.onPause(ctx);
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        if (digger != null) {
            digger.stop(ctx);
            digger = null;
        }
        if (miner != null) {
            miner.stop(ctx);
            miner = null;
        }
        if (sweeper != null) {
            sweeper.stop(ctx);
            sweeper = null;
        }
        if (returnTask != null) {
            returnTask.stop(ctx);
            returnTask = null;
        }
        if (relocationTask != null) {
            relocationTask.stop(ctx);
            relocationTask = null;
        }
        if (stagingTask != null) {
            stagingTask.stop(ctx);
            stagingTask = null;
        }
        ctx.input.reset();
    }
}
