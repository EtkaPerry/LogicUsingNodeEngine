package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.BlockBreaker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * Digs a straight corridor of a given height, one block at a time.
 * <p>
 * Each step clears the whole column ahead before moving into it, so the bot never walks into a
 * half-dug space and never leaves a floating block over its head. Digging uses the pathfinder only
 * for the single step forward, which keeps the corridor straight rather than letting A* wander off
 * through whatever gap it finds.
 */
public final class TunnelTask implements Task {

    /** Value of the direction parameter meaning "whichever way I'm facing when this starts". */
    public static final String FACING = "Facing";

    private final String directionChoice;
    private final int length;
    private final int height;

    private final BlockBreaker breaker = new BlockBreaker();
    private BlockPos origin;
    private Direction direction;
    private int advanced;
    private GotoTask stepForward;
    private String status = "";

    public TunnelTask(String directionChoice, int length, int height) {
        this.directionChoice = directionChoice;
        this.length = length;
        this.height = Math.max(2, height);
    }

    /** For Stripmine, which knows its direction up front and picks its own starting point. */
    public TunnelTask(Direction direction, BlockPos origin, int length, int height) {
        this(direction.getName(), length, height);
        this.direction = direction;
        this.origin = origin;
    }

    @Override
    public String name() {
        return "Tunnel";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(advanced, length, "blocks");
    }

    @Override
    public void onStart(BotContext ctx) {
        if (origin == null) {
            origin = ctx.player.blockPosition();
        }
        if (direction == null) {
            direction = FACING.equalsIgnoreCase(directionChoice)
                    ? ctx.player.getDirection()
                    : parseDirection(directionChoice, ctx.player.getDirection());
        }
    }

    private static Direction parseDirection(String name, Direction fallback) {
        for (Direction candidate : Direction.Plane.HORIZONTAL) {
            if (candidate.getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return fallback;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (advanced >= length) {
            breaker.stop(ctx);
            status = "dug " + advanced + " blocks";
            return TaskStatus.SUCCESS;
        }

        BlockPos ahead = origin.relative(direction, advanced + 1);

        // Clear the full column before stepping into it, lowest block first so the bot always has
        // somewhere to stand.
        for (int y = 0; y < height; y++) {
            BlockPos block = ahead.above(y);
            if (MovementHelper.isPassable(ctx.level, block)) {
                continue;
            }
            if (breaker.isOutOfReach(ctx, block)) {
                // Shouldn't normally happen one block ahead, but knockback and falls happen.
                return walkTo(ctx, ahead, "repositioning");
            }
            BlockBreaker.Progress progress = breaker.tick(ctx, block, true);
            if (progress == BlockBreaker.Progress.NO_TOOL) {
                status = MineTask.NO_TOOL_PREFIX + " to break "
                        + ctx.level.getBlockState(block).getBlock().getName().getString();
                return TaskStatus.FAILED;
            }
            if (progress == BlockBreaker.Progress.HAZARD) {
                status = breaker.getFailureReason();
                return TaskStatus.FAILED;
            }
            status = "digging " + (advanced + 1) + "/" + length;
            return TaskStatus.RUNNING;
        }
        breaker.stop(ctx);

        if (Mth.floor(ctx.player.getX()) == ahead.getX() && Mth.floor(ctx.player.getZ()) == ahead.getZ()) {
            advanced++;
            if (stepForward != null) {
                stepForward.stop(ctx);
                stepForward = null;
            }
            return TaskStatus.RUNNING;
        }
        return walkTo(ctx, ahead, "advancing " + (advanced + 1) + "/" + length);
    }

    private TaskStatus walkTo(BotContext ctx, BlockPos target, String what) {
        if (stepForward == null) {
            stepForward = new GotoTask(new Goals.Block(target), false, true);
            stepForward.start(ctx);
        }
        TaskStatus result = stepForward.tick(ctx);
        if (result == TaskStatus.FAILED) {
            status = "couldn't move into the corridor";
            return TaskStatus.FAILED;
        }
        status = what;
        return TaskStatus.RUNNING;
    }

    /** Where the corridor has reached, so Stripmine can start a branch from it. */
    public BlockPos currentEnd() {
        return origin == null ? null : origin.relative(direction, advanced);
    }

    @Override
    public void onPause(BotContext ctx) {
        breaker.stop(ctx);
        if (stepForward != null) {
            stepForward.stop(ctx);
            stepForward = null;
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        breaker.stop(ctx);
        if (stepForward != null) {
            stepForward.stop(ctx);
            stepForward = null;
        }
        ctx.input.reset();
    }
}
