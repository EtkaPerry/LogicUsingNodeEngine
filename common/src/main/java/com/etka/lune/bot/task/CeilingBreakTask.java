package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import net.minecraft.core.BlockPos;

/**
 * Frees the player's own body space, and the headroom above it, when a movement goal has left them
 * in a pocket or standing inside a block.
 *
 * <p>This is deliberately a recovery, not a second mining strategy. It only targets the two blocks
 * the player's body occupies and the one directly above them, uses the shared crosshair/ray based
 * breaker, and stops the moment there is room to stand and jump. It therefore cannot tunnel toward
 * a hidden goal or make a food scout become an underground search.</p>
 */
final class CeilingBreakTask implements Task {

    private static final int BLOCK_TIMEOUT_TICKS = 80;

    private final int maxBlocks;
    private final BlockBreaker breaker = new BlockBreaker();
    private int cleared;
    private int ticksOnBlock;
    private BlockPos target;
    private String status = "";

    CeilingBreakTask(int maxBlocks) {
        this.maxBlocks = Math.max(1, maxBlocks);
    }

    @Override
    public String name() {
        return "Clear obstruction";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        BlockPos body = blockedBody(ctx, feet);
        if (body == null && hasHeadroom(ctx, feet)) {
            status = "obstruction cleared - " + cleared + " blocks";
            return TaskStatus.SUCCESS;
        }
        if (cleared >= maxBlocks) {
            status = "ceiling recovery limit reached";
            return TaskStatus.FAILED;
        }

        // The body comes first: a player standing inside leaves cannot move at all, so clearing the
        // block above their head would leave them exactly as stuck as before.
        BlockPos next = body != null ? body : feet.above(2);
        if (!next.equals(target)) {
            breaker.stop(ctx);
            target = next;
            ticksOnBlock = 0;
        }
        if (!MovementHelper.isBreakable(ctx.level, target)) {
            breaker.stop(ctx);
            status = "ceiling is not breakable";
            return TaskStatus.FAILED;
        }
        if (MovementHelper.wouldOpenLava(ctx.level, target)
                || MovementHelper.wouldOpenWater(ctx.level, target)) {
            breaker.stop(ctx);
            status = "refusing unsafe ceiling breach";
            return TaskStatus.FAILED;
        }
        if (++ticksOnBlock > BLOCK_TIMEOUT_TICKS) {
            breaker.stop(ctx);
            status = "could not clear the ceiling";
            return TaskStatus.FAILED;
        }

        BlockBreaker.Progress progress = breaker.tick(ctx, target, false);
        if (progress == BlockBreaker.Progress.NO_TOOL) {
            status = "no tool for the ceiling";
            return TaskStatus.FAILED;
        }
        if (progress == BlockBreaker.Progress.HAZARD) {
            status = breaker.getFailureReason();
            return TaskStatus.FAILED;
        }
        if (ctx.level.getBlockState(target).isAir()
                || MovementHelper.isPassable(ctx.level, target)) {
            breaker.stop(ctx);
            cleared++;
            target = null;
            ticksOnBlock = 0;
        }
        status = "clearing obstruction - " + cleared + "/" + maxBlocks;
        return TaskStatus.RUNNING;
    }

    private static boolean hasHeadroom(BotContext ctx, BlockPos feet) {
        return MovementHelper.isPassable(ctx.level, feet.above(2));
    }

    private static BlockPos blockedBody(BotContext ctx, BlockPos feet) {
        return MovementHelper.blockedBodyPos(ctx.level, feet, ctx.player.getOnPos(),
                ctx.player.onGround());
    }

    @Override
    public void onStop(BotContext ctx) {
        breaker.stop(ctx);
        ctx.input.reset();
    }
}
