package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import net.minecraft.core.BlockPos;

/** Walks a chosen distance in a cardinal or player-relative direction. */
public final class DirectionalGotoTask implements Task {

    private final String label;
    private final String direction;
    private final int distance;
    private final int tolerance;
    private final boolean sprint;

    private GotoTask delegate;
    private BlockPos target;

    public DirectionalGotoTask(String label, String direction, int distance, int tolerance, boolean sprint) {
        this.label = label;
        this.direction = direction;
        this.distance = Math.max(1, distance);
        // A tolerance equal to the requested distance would make the task succeed without taking
        // a step. Keep directional travel meaningful even if an old coordinate node stored 32.
        this.tolerance = tolerance >= distance ? 1 : Math.max(0, tolerance);
        this.sprint = sprint;
    }

    @Override
    public String name() {
        return label + " " + direction + " " + distance + " blocks";
    }

    /**
     * Directional travel is one job, not one job per compass point.
     *
     * <p>The default context is built from {@link #name()}, which names the request rather than
     * the work: "Walk East 64 blocks" and "Walk North 64 blocks" would keep separate timing
     * baselines for the same walking. What actually differs between them is how far and whether
     * the bot may sprint, and the route inside is already learned by {@link GotoTask} under
     * "movement" - this entry only times the wrapper.</p>
     */
    @Override
    public com.etka.lune.bot.learning.LearningContext learningContext(BotContext ctx) {
        String dimension = ctx == null || ctx.level == null
                ? "unknown" : ctx.level.dimension().identifier().toString();
        return new com.etka.lune.bot.learning.LearningContext("skill", "directional-travel",
                dimension, "distance=" + MovementPolicy.distanceBucket(distance)
                        + ";sprint=" + sprint);
    }

    @Override
    public String status() {
        if (delegate == null) {
            return target == null ? Lang.get("lune.status.directional_goto.choosing_destination")
                : Lang.get("lune.status.directional_goto.heading_to", describe(target));
        }
        String detail = delegate.status();
        return "heading " + direction.toLowerCase() + " to " + describe(target)
                + (detail.isBlank() ? "" : " - " + detail);
    }

    @Override
    public void onStart(BotContext ctx) {
        target = resolveTarget(ctx);
        // Directional travel follows terrain; only X/Z distance matters, unlike exact coordinates.
        delegate = new GotoTask(new Goals.NearXZ(target.getX(), target.getZ(), tolerance), sprint, false);
        delegate.start(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (delegate == null) {
            onStart(ctx);
        }
        return delegate.tick(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (delegate != null) {
            delegate.stop(ctx);
            delegate = null;
        }
        ctx.input.reset();
    }

    private BlockPos resolveTarget(BotContext ctx) {
        double dx;
        double dz;
        switch (direction) {
            case "North" -> { dx = 0; dz = -1; }
            case "South" -> { dx = 0; dz = 1; }
            case "East" -> { dx = 1; dz = 0; }
            case "West" -> { dx = -1; dz = 0; }
            default -> {
                double yaw = Math.toRadians(ctx.player.getYRot());
                double offset = switch (direction) {
                    case "Back" -> Math.PI;
                    case "Left" -> -Math.PI / 2.0;
                    case "Right" -> Math.PI / 2.0;
                    default -> 0.0;
                };
                dx = -Math.sin(yaw + offset);
                dz = Math.cos(yaw + offset);
            }
        }
        return new BlockPos(
                (int) Math.floor(ctx.player.getX() + dx * distance),
                ctx.player.blockPosition().getY(),
                (int) Math.floor(ctx.player.getZ() + dz * distance));
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
