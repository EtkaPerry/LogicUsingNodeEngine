package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.path.MovementHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reconnects a caller to nearby dry ground after it starts in water or finishes in a pocket.
 *
 * <p>This is deliberately a local heightmap search. It only considers loaded columns and the
 * returned destination must be a real two-block-high dry standing position. The route itself may
 * swim because the caller explicitly asked for recovery; ordinary exploration remains no-swim.</p>
 */
public final class SurfaceRecoveryTask implements Task {
    private static final int SEARCH_RADIUS = 48;

    private BlockPos target;
    private GotoTask route;
    private boolean recovered;
    private final StatusText status = new StatusText();

    @Override
    public String name() {
        return Lang.get("lune.task.surface_recovery.return_surface");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Return to Surface");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public boolean madeProgress() {
        return recovered;
    }

    @Override
    public void onStart(BotContext ctx) {
        target = findSurfaceTarget(ctx);
        route = null;
        recovered = false;
        if (target == null) {
            status.set("lune.status.surface_recovery.no_nearby_walkable_surface");
            return;
        }
        route = new GotoTask(new Goals.Block(target), true, true);
        route.start(ctx);
        status.set("lune.status.surface_recovery.leaving_pocket_2", target.toShortString());
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (target == null || route == null) {
            status.set("lune.status.surface_recovery.no_nearby_walkable_surface");
            return TaskStatus.FAILED;
        }

        TaskStatus result = route.tick(ctx);
        status.set("lune.status.surface_recovery.leaving_pocket", route.statusLine());
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }

        route.stop(ctx);
        route = null;
        if (result == TaskStatus.SUCCESS && reconnected(ctx, ctx.player.blockPosition())) {
            recovered = true;
            status.set("lune.status.surface_recovery.reached_walkable_surface");
            return TaskStatus.SUCCESS;
        }
        if (result == TaskStatus.FAILED) {
            status.set("lune.status.surface_recovery.could_not_reconnect_walkable_surface");
        } else {
            status.set("lune.status.surface_recovery.route_ended_before_reaching_walkable");
        }
        return TaskStatus.FAILED;
    }

    @Override
    public void onStop(BotContext ctx) {
        if (route != null) {
            route.stop(ctx);
            route = null;
        }
        ctx.input.reset();
    }

    /** True when feet are on the top walkable band of a loaded dry column. */
    public static boolean isOnDrySurface(BotContext ctx, BlockPos feet) {
        BlockPos surface = findDrySurface(ctx, feet.getX(), feet.getZ());
        return surface != null && Math.abs(surface.getY() - feet.getY()) <= 1;
    }

    /**
     * True when this position genuinely answers the question the caller asked, which is stricter
     * than being level with the surface.
     *
     * <p>Standing inside a tree canopy is level with the dry column underneath it, so the plain
     * height test calls it recovered - and then {@link #needsDryGroundRecovery} immediately asks
     * again, because the body is still inside the leaves. Requiring a real standing position here
     * makes the task route down out of the canopy instead of reporting a success that changed
     * nothing.</p>
     */
    private static boolean reconnected(BotContext ctx, BlockPos feet) {
        return isOnDrySurface(ctx, feet) && MovementHelper.canStandAt(ctx.level, feet, false);
    }

    /**
     * True when a caller needs to reconnect with the surface before a surface-only job can act.
     *
     * <p>A cave floor is a valid standing position, but it is not a useful starting point for a
     * visible wood/food scout. Treat a loaded surface several blocks above the player as another
     * recovery case, so a broken tool does not make the next wood search stare at a cave ceiling.
     */
    public static boolean needsDryGroundRecovery(BotContext ctx) {
        BlockPos feet = ctx.player.blockPosition();
        if (!MovementHelper.canStandAt(ctx.level, feet, false)) {
            return true;
        }
        BlockPos surface = findDrySurface(ctx, feet.getX(), feet.getZ());
        return surface != null && surface.getY() - feet.getY() >= 4;
    }

    private static BlockPos findSurfaceTarget(BotContext ctx) {
        BlockPos current = ctx.player.blockPosition();
        if (reconnected(ctx, current)) {
            return current;
        }

        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int radius = 0; radius <= SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos probe = new BlockPos(current.getX() + dx, ctx.level.getMinY(),
                            current.getZ() + dz);
                    if (!ctx.level.hasChunkAt(probe)) {
                        continue;
                    }
                    BlockPos surface = findDrySurface(ctx, probe.getX(), probe.getZ());
                    if (surface == null || !ctx.level.hasChunkAt(surface)) {
                        continue;
                    }
                    int horizontal = Math.abs(dx) + Math.abs(dz);
                    int vertical = Math.abs(surface.getY() - current.getY());
                    int score = horizontal + vertical * 6;
                    if (score < bestScore) {
                        best = surface;
                        bestScore = score;
                    }
                }
            }
            if (best != null && radius >= 6) {
                return best;
            }
        }
        return best;
    }

    /** Resolve a heightmap column to a walkable dry feet position. */
    public static BlockPos findDrySurface(BotContext ctx, int x, int z) {
        int surface = ctx.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int lower = Math.max(ctx.level.getMinY() + 1, surface - 4);
        int upper = Math.min(ctx.level.getMaxY() - 2, surface + 2);
        for (int y = upper; y >= lower; y--) {
            BlockPos feet = new BlockPos(x, y, z);
            if (!MovementHelper.isWater(ctx.level, feet)
                    && !MovementHelper.isWater(ctx.level, feet.above())
                    && MovementHelper.canStandAt(ctx.level, feet, false)) {
                return feet;
            }
        }
        return null;
    }
}
