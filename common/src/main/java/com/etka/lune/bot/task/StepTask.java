package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Moves a block or two sideways without turning.
 * <p>
 * Walk and Run hand the job to the pathfinder, which turns the body to face where it is going -
 * correct for travelling, and useless for building, where every card after this one is aimed
 * relative to where the bot is looking. Take one step right with Walk and "in front of me" now
 * means a different wall.
 * <p>
 * So this card holds the view exactly where it is and strafes: the keys are worked out in the
 * player's own frame each tick by {@link StepPolicy}, aimed at the middle of the block being
 * stepped into. A row of blocks is then Place Block and this, in a loop, and it comes out straight.
 * <p>
 * It sneaks by default, which is both the precise way to move and the reason it will not walk off
 * the wall it is standing on.
 */
public final class StepTask implements Task {

    /** Below this, an axis counts as arrived; it is what stops the bot juddering over the centre. */
    private static final double DEAD_ZONE = 0.06;
    /** Let go this far out when walking, since a walking player slides after the key comes up. */
    private static final double COAST_WALKING = 0.32;
    /** Sneaking barely slides at all, so it can be steered almost to the middle. */
    private static final double COAST_SNEAKING = 0.10;
    /** Ticks of coasting before the landing is judged. */
    private static final int SETTLE_TICKS = 4;
    /** Ticks without getting closer before the way is called blocked. */
    private static final int STALL_TICKS = 20;
    /** Nudges allowed after a settle that stopped on the wrong block. */
    private static final int MAX_ATTEMPTS = 3;

    private final StepPolicy.Side side;
    private final int blocks;
    private final boolean sneak;

    private BlockPos target;
    private Direction axis;
    private int attempts;
    private int settling;
    private int stalledTicks;
    private int ticks;
    private double closest = Double.MAX_VALUE;
    private boolean arrived;
    private final StatusText status = new StatusText();

    public StepTask(StepPolicy.Side side, int blocks, boolean sneak) {
        this.side = side == null ? StepPolicy.Side.RIGHT : side;
        this.blocks = Math.clamp(blocks, 1, 16);
        this.sneak = sneak;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.step.name", com.etka.lune.bot.command.Param.Choice.optionLabel(side.label()), blocks);
    }

    /** English on purpose: this is the learner's row key, and is never shown. */
    @Override
    public String learningId() {
        return Task.learningName("Step " + side.label().toLowerCase() + " " + blocks);
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(arrived ? blocks : 0, blocks, Lang.get("lune.card.blocks_unit"));
    }

    @Override
    public boolean madeProgress() {
        return arrived;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (target == null) {
            // Pinned on the first tick and never re-read: a target taken from where the bot is
            // standing would walk away from itself, a fraction of a block at a time.
            axis = StepPolicy.axis(ctx.player.getDirection(), side);
            target = ctx.player.blockPosition().relative(axis, blocks);
        }

        if (++ticks > deadline()) {
            status.set("lune.status.step.could_not_step", com.etka.lune.bot.command.Param.Choice.optionLabel(side.label()));
            return TaskStatus.FAILED;
        }

        if (settling > 0) {
            settling--;
            ctx.input.sneak = sneak;
            if (settling == 0) {
                return judgeLanding(ctx);
            }
            status.set("lune.status.step.stopping");
            return TaskStatus.RUNNING;
        }

        double dx = target.getX() + 0.5 - ctx.player.getX();
        double dz = target.getZ() + 0.5 - ctx.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance < closest - 0.02) {
            closest = distance;
            stalledTicks = 0;
        } else if (++stalledTicks > STALL_TICKS) {
            status.set("lune.status.step.something_way");
            return TaskStatus.FAILED;
        }

        if (distance <= (sneak ? COAST_SNEAKING : COAST_WALKING)) {
            settling = SETTLE_TICKS;
            status.set("lune.status.step.stopping");
            ctx.input.sneak = sneak;
            return TaskStatus.RUNNING;
        }

        StepPolicy.Impulse impulse = StepPolicy.toward(dx, dz, ctx.player.getYRot(), DEAD_ZONE);
        ctx.input.forward = impulse.forward();
        ctx.input.backward = impulse.backward();
        ctx.input.left = impulse.left();
        ctx.input.right = impulse.right();
        ctx.input.sneak = sneak;
        // A step onto the block it just placed is the ordinary case for a builder, and it is the
        // one thing a walking player does without thinking. Only once the way is clearly barred,
        // so an ordinary step never jumps.
        if (stalledTicks > STALL_TICKS / 3 && canStepUp(ctx)) {
            ctx.input.jump = true;
        }

        status.set("lune.status.step.stepping", com.etka.lune.bot.command.Param.Choice.optionLabel(side.label()), String.format(java.util.Locale.ROOT, "%.1f", Math.max(0, blocks - distance)), blocks);
        return TaskStatus.RUNNING;
    }

    /** Success is about which block the bot is standing in; the fraction of a block is not the job. */
    private TaskStatus judgeLanding(BotContext ctx) {
        BlockPos here = ctx.player.blockPosition();
        if (here.getX() == target.getX() && here.getZ() == target.getZ()) {
            arrived = true;
            status.set("lune.status.step.stepped", blocks, com.etka.lune.bot.command.Param.Choice.optionLabel(side.label()));
            return TaskStatus.SUCCESS;
        }
        if (++attempts >= MAX_ATTEMPTS) {
            status.set("lune.status.step.stopped_one_block_short", target.toShortString());
            return TaskStatus.FAILED;
        }
        // Close but on the wrong side of the boundary. Steer again rather than declaring a step
        // that visibly did not happen.
        closest = Double.MAX_VALUE;
        stalledTicks = 0;
        status.set("lune.status.step.adjusting");
        return TaskStatus.RUNNING;
    }

    /** Whether the blocked step is a step up onto something, rather than into a wall. */
    private boolean canStepUp(BotContext ctx) {
        BlockPos ahead = ctx.player.blockPosition().relative(axis);
        return MovementHelper.isSolidFloor(ctx.level, ahead)
                && MovementHelper.isPassable(ctx.level, ahead.above())
                && MovementHelper.isPassable(ctx.level, ahead.above(2));
    }

    private int deadline() {
        // Sneaking is about a third of walking pace, and a block is roughly five walking ticks.
        return 40 + blocks * (sneak ? 60 : 25);
    }

    @Override
    public void onStop(BotContext ctx) {
        ctx.input.reset();
    }

    /** Where the step was aimed, once it has been worked out. Used by tests and the debug overlay. */
    public Vec3 targetCentre() {
        return target == null ? null : Vec3.atBottomCenterOf(target);
    }
}
