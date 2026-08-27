package com.etka.lune.bot.path;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.util.BlockBreaker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Emergency, non-destructive route to breathable air through connected water. */
public final class WaterEscape {

    private static final int SEARCH_RADIUS = 16;
    /** Blocks of ceiling worth cutting through on the way up; more than this and the air runs out. */
    private static final int CEILING_BREAK_REACH = 3;
    /** Shared breaker for the ceiling cut; only ever used while this class is driving the player. */
    private static final BlockBreaker CEILING_BREAKER = new BlockBreaker();
    private static final int MAX_VISITED = 4_096;
    private static final Direction[] SEARCH_ORDER = {
            Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST, Direction.DOWN
    };

    private WaterEscape() {}

    /** Starts recovery with roughly six seconds of vanilla air left. */
    public static boolean needsAir(LocalPlayer player) {
        int threshold = Math.max(60, player.getMaxAirSupply() * 2 / 5);
        return player.isUnderWater() && player.getAirSupply() <= threshold;
    }

    /**
     * Takes over movement while air is low. It follows connected water to the nearest surface or
     * air pocket and never digs, so recovery cannot turn a small leak into a larger flood.
     */
    public static boolean tick(BotContext ctx) {
        if (!needsAir(ctx.player)) {
            return false;
        }

        tickToAir(ctx);
        return true;
    }

    /** Forces the same escape movement for a configurable Self Preservation air condition. */
    public static void tickToAir(BotContext ctx) {

        BlockPos start = ctx.player.blockPosition();
        BlockPos next = firstStepToAir(ctx.level, start, SEARCH_RADIUS);
        ctx.input.sprint = false;
        ctx.debug.lastEvent = next == null ? "low air - swimming up" : "low air - escaping water";

        if (next == null) {
            // Nothing swimmable leads to air. Pressing jump here is holding the body against a
            // ceiling until the air runs out, which is how a run drowns under an overhang with a
            // block of gravel between it and the sky. Cut through it - that is what a person does,
            // and one block is usually all that is in the way.
            if (breakCeiling(ctx)) {
                return;
            }
            ctx.input.jump = true;
            return;
        }
        if (next.getY() > start.getY()) {
            ctx.input.jump = true;
            return;
        }
        if (next.getY() < start.getY()) {
            ctx.input.sneak = true;
        }

        Vec3 target = Vec3.atCenterOf(next);
        ctx.look.lookAt(ctx.player, new Vec3(target.x, ctx.player.getEyeY(), target.z));
        steer(ctx.player, ctx, target);
        // Stay near the surface on horizontal legs, but don't fight an intentionally downward
        // first step needed to get out from under an overhang.
        if (next.getY() == start.getY()) {
            ctx.input.jump = true;
        }
    }

    /**
     * Mines straight up toward the surface when swimming out is not an option.
     *
     * <p>Bounded hard, because this runs while the air is already low: only the first few blocks of
     * the column, only material that can actually be broken, and never anything that would let lava
     * in. If none of that applies the caller falls back to holding jump, which at least keeps the
     * body high in the water.
     *
     * @return true when a break is under way and the caller should not also steer
     */
    private static boolean breakCeiling(BotContext ctx) {
        BlockPos head = ctx.player.blockPosition().above();
        for (int step = 0; step < CEILING_BREAK_REACH; step++) {
            BlockPos above = head.above(step);
            if (!ctx.level.isLoaded(above)) {
                return false;
            }
            if (MovementHelper.isWater(ctx.level, above)) {
                continue;
            }
            if (!MovementHelper.isBreakable(ctx.level, above)
                    || MovementHelper.isLava(ctx.level, above)
                    || MovementHelper.wouldOpenLava(ctx.level, above)) {
                return false;
            }
            ctx.input.jump = true;
            ctx.look.urgent();
            ctx.look.lookAt(ctx.player, Vec3.atCenterOf(above));
            ctx.debug.lastEvent = "low air - cutting up through the ceiling";
            return CEILING_BREAKER.tick(ctx, above) != BlockBreaker.Progress.NO_TOOL;
        }
        return false;
    }

    /**
     * A water breach is only considered survivable when the connected water has a nearby surface
     * or air pocket. This is intentionally conservative for sealed flooded caves.
     */
    public static boolean hasBreathableExit(BlockGetter level, BlockPos brokenBlock) {
        for (Direction direction : Direction.values()) {
            BlockPos water = brokenBlock.relative(direction);
            if (MovementHelper.isWater(level, water)
                    && firstStepToAir(level, water, SEARCH_RADIUS) != null) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos firstStepToAir(BlockGetter level, BlockPos start, int radius) {
        if (!MovementHelper.isWater(level, start)) {
            return null;
        }

        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        open.add(start);
        seen.add(start);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED) {
            BlockPos current = open.removeFirst();
            if (isBreathableSurface(level, current)) {
                return firstStep(start, current, parent);
            }

            for (Direction direction : SEARCH_ORDER) {
                BlockPos next = current.relative(direction);
                if (Math.abs(next.getX() - start.getX()) > radius
                        || Math.abs(next.getY() - start.getY()) > radius
                        || Math.abs(next.getZ() - start.getZ()) > radius
                        || !MovementHelper.isWater(level, next)
                        || MovementHelper.isLava(level, next)
                        || !seen.add(next)) {
                    continue;
                }
                parent.put(next, current);
                open.addLast(next);
            }
        }
        return null;
    }

    private static boolean isBreathableSurface(BlockGetter level, BlockPos water) {
        return MovementHelper.isWater(level, water)
                && !MovementHelper.isWater(level, water.above())
                && MovementHelper.isPassable(level, water.above());
    }

    private static BlockPos firstStep(BlockPos start, BlockPos end, Map<BlockPos, BlockPos> parent) {
        BlockPos step = end;
        BlockPos previous = parent.get(step);
        while (previous != null && !previous.equals(start)) {
            step = previous;
            previous = parent.get(step);
        }
        // Already at the surface: upward swimming is the required action.
        return step.equals(start) ? start.above() : step;
    }

    private static void steer(LocalPlayer player, BotContext ctx, Vec3 target) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        float desiredYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        double relative = Mth.wrapDegrees(desiredYaw - player.getYRot());
        if (Math.abs(relative) < 67.5) ctx.input.forward = true;
        if (Math.abs(relative) > 112.5) ctx.input.backward = true;
        if (relative >= 22.5 && relative <= 157.5) ctx.input.right = true;
        if (relative <= -22.5 && relative >= -157.5) ctx.input.left = true;
    }
}
