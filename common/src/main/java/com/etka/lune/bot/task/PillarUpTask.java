package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningContext;
import com.etka.lune.bot.catalog.BlockCatalog;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockPlacer;
import net.minecraft.world.phys.Vec3;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * Builds a one-block pillar underfoot to climb out of a hole.
 * <p>
 * This is the thing a player does without thinking when they have dug themselves into somewhere
 * they cannot walk out of: jump, place a block under your feet, repeat. Without it a bot that digs
 * its own staircase can finish the job and then have no way back to the surface, and every task
 * waiting behind it stalls forever.
 * <p>
 * Deliberately small and dumb. It gains height and nothing else - the caller re-paths afterwards
 * and decides what the new position is good for.
 */
public final class PillarUpTask implements Task {

    /**
     * How far the feet must clear the target block before the placement is worth asking for.
     *
     * <p>A vanilla jump peaks around 1.25 blocks, so half a block is comfortably inside the hop and
     * still far enough that the player is no longer occupying the space being filled.
     */
    /** Ticks allowed per block before assuming the placement is never going to land. */
    private static final int PLACE_TIMEOUT_TICKS = 20;

    private final int maxHeight;

    private int startY;
    private int lastY;
    private int placeTicks;
    private int failedPlacementTicks;
    /** The block this attempt is filling, fixed so the rise is measured against solid ground. */
    private BlockPos pillarFrom;
    /** The block we most recently asked vanilla to place, awaiting confirmation and a real rise. */
    private BlockPos pendingTarget;
    private Block pendingMaterial;
    private String placementStrategy = PillarPolicy.DEFAULT;
    private String status = "";

    public PillarUpTask(int maxHeight) {
        this.maxHeight = Math.max(1, maxHeight);
    }

    @Override
    public String name() {
        return "Pillar up";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public boolean madeProgress() {
        return lastY > startY;
    }

    /** How many blocks of height this actually gained. */
    public int gained() {
        return Math.max(0, lastY - startY);
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(gained(), maxHeight, "height blocks");
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        String phase = "height=" + PillarPolicy.heightBucket(maxHeight)
                + ";ceiling=" + !MovementHelper.isPassable(
                        ctx.level, ctx.player.blockPosition().above(2));
        return new LearningContext("skill", "pillar-building",
                ctx.level.dimension().identifier().toString(), phase);
    }

    @Override
    public List<String> learningActions(BotContext ctx) {
        return PillarPolicy.ACTIONS;
    }

    @Override
    public void onLearningAction(BotContext ctx, String action) {
        placementStrategy = PillarPolicy.ACTIONS.contains(action) ? action : PillarPolicy.DEFAULT;
    }

    @Override
    public void onStart(BotContext ctx) {
        startY = ctx.player.blockPosition().getY();
        lastY = startY;
        placeTicks = 0;
        failedPlacementTicks = 0;
        pendingTarget = null;
        pendingMaterial = null;
        pillarFrom = null;
        placementStrategy = PillarPolicy.DEFAULT;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        if (pendingTarget != null && pendingMaterial != null
                && ctx.level.getBlockState(pendingTarget).is(pendingMaterial)) {
            if (feet.getY() > lastY) {
                // A jump is not progress by itself: the old implementation counted the first
                // airborne tick as a climb, then rebuilt the recovery at the original Y level.
                // Only accept the height after the exact block we requested exists underneath us.
                lastY = feet.getY();
                placeTicks = 0;
                failedPlacementTicks = 0;
                pendingTarget = null;
                pendingMaterial = null;
                pillarFrom = null;
            } else {
                // The click landed, but physics has not carried the player onto the new block
                // yet. Keep jumping and wait for the world/player state to agree before placing
                // another step.
                ctx.input.jump = true;
                status = "waiting to rise onto the placed pillar block";
                return TaskStatus.RUNNING;
            }
        }

        int gained = gained();
        if (gained >= maxHeight) {
            status = "climbed " + gained + " blocks";
            return TaskStatus.SUCCESS;
        }

        // Jumping needs somewhere to jump into. Under a ceiling this can never work, so stop rather
        // than bounce against the rock until the timeout.
        if (!MovementHelper.isPassable(ctx.level, feet.above(2))) {
            status = gained > 0 ? "climbed " + gained + " blocks, ceiling above" : "no room above to climb";
            return gained > 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }

        Block material = buildingMaterial(ctx);
        if (material == null) {
            status = gained > 0 ? "climbed " + gained + " blocks, out of blocks" : "nothing to build with";
            return gained > 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }

        // Jump first, place at the top of the hop - the timing a player uses.
        //
        // Asking for the placement on the same tick as the jump is asking to fill a block the
        // player is still standing in, so vanilla refuses and the attempt is spent. Pressing jump
        // and then waiting until the body has cleared the block leaves a real gap to place into,
        // and the head goes down in the same motion because that is where the block is aimed.
        //
        // The block being filled has to be pinned for the whole attempt. Measuring the rise against
        // player.blockPosition() measures against a moving reference: the moment the jump carries
        // the player past the top of the block their feet position moves up too and the clearance
        // resets to zero, so the gate never opened. The journal shows the result - four ticks spent
        // waiting for every one spent placing.
        if (pillarFrom == null) {
            pillarFrom = feet.immutable();
        }
        ctx.input.jump = true;
        ctx.look.lookAt(ctx.player, Vec3.atCenterOf(pillarFrom.below()));
        double risen = ctx.player.getY() - pillarFrom.getY();
        if (ctx.player.onGround() || risen < PillarPolicy.clearance(placementStrategy)) {
            status = "jumping to make room for the next pillar block";
            return TaskStatus.RUNNING;
        }
        feet = pillarFrom;
        // The timeout counts placement attempts, not the ticks spent in the air waiting for one.
        // Counting the hop against it meant a slow jump could exhaust the budget before a single
        // placement had been tried.
        if (++placeTicks > PLACE_TIMEOUT_TICKS) {
            status = gained > 0 ? "climbed " + gained + " blocks" : "could not place a block underfoot";
            return gained > 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }
        BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(ctx, material, feet);
        if (placement == BlockPlacer.PlacementResult.BLOCKED
                || placement == BlockPlacer.PlacementResult.NO_MATERIAL
                || placement == BlockPlacer.PlacementResult.NO_SUPPORT
                || placement == BlockPlacer.PlacementResult.OUT_OF_REACH) {
            status = gained > 0 ? "climbed " + gained + " blocks; " + placement.name().toLowerCase()
                    : "cannot place pillar block: " + placement.name().toLowerCase();
            ctx.debug.decide("stop pillar: placement is not possible at " + feet.toShortString());
            return gained > 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }
        // Remember the exact target and material even when the client reports only a click. The
        // server confirmation and the subsequent rise are what establish real progress.
        pendingTarget = feet.immutable();
        pendingMaterial = material;
        if (placement.isTransient() && ++failedPlacementTicks > PLACE_TIMEOUT_TICKS) {
            status = gained > 0 ? "climbed " + gained + " blocks; placement timed out"
                    : "could not place a block underfoot";
            ctx.debug.decide("give up pillar: placement did not change after "
                    + PLACE_TIMEOUT_TICKS + " ticks");
            return gained > 0 ? TaskStatus.SUCCESS : TaskStatus.FAILED;
        }
        status = "building a way out - " + gained + "/" + maxHeight;
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(BotContext ctx) {
        ctx.input.reset();
        pendingTarget = null;
        pendingMaterial = null;
    }

    /** The first block in the build palette the bot is actually carrying. */
    private static Block buildingMaterial(BotContext ctx) {
        for (Block candidate : BlockCatalog.buildingBlocks()) {
            Item item = candidate.asItem();
            if (item != null && InventoryHelper.findSlot(ctx.player, stack -> stack.is(item)) >= 0) {
                return candidate;
            }
        }
        return null;
    }
}
