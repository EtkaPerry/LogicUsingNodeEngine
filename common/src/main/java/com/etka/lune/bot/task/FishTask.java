package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.learning.LearningContext;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Casts, watches the bobber, and reels in on a bite.
 * <p>
 * The client is never told "a fish bit" directly, so the bite is detected the same way a player
 * sees it: the bobber gets yanked sharply downward while sitting in water. A short cooldown after
 * every rod use keeps it from double-casting on the tick the hook appears.
 */
public final class FishTask implements Task {

    /** Downward movement that means a bite rather than ordinary surface bobbing. */
    private static final double BITE_VELOCITY = -0.08;
    private static final double BITE_DROP = 0.10;
    /** Let the bobber settle before interpreting a downward movement as a bite. */
    private static final int MIN_WATER_TICKS = 8;
    /**
     * Ticks the hook may read as out of water before the observation is abandoned.
     *
     * <p>Long enough to ride out the surface animation, short enough that a bobber genuinely thrown
     * onto land still gives up quickly - and the land case never reaches this anyway, because it
     * requires the hook to have been in the water first.</p>
     */
    private static final int BOB_TOLERANCE = 4;
    /** Ticks to wait after using the rod, so one action isn't sent several times. */
    private static final int USE_COOLDOWN = 20;
    /** Maximum time to wait for the client/server to expose the new bobber. */
    private static final int CAST_CONFIRM_TIMEOUT = 100;
    /** A missed cast should be reeled back and retried instead of waiting on land forever. */
    private static final int CAST_FLIGHT_TIMEOUT = 35;
    /** The server may remove a caught hook before the local player clears its reference. */
    private static final int REEL_SYNC_TIMEOUT = 40;
    /** Search only a small, player-like area for a water block to aim at. */
    private static final int WATER_SEARCH_RADIUS = 12;
    private static final int WATER_SEARCH_VERTICAL = 2;
    /** Nearby visible water used to identify the middle rather than the closest shoreline. */
    private static final int CENTER_SAMPLE_RADIUS = 4;
    /** Do not aim at water under the player's feet or at the block being stood on. */
    private static final double MIN_WATER_HORIZONTAL_SQR = 1.0;
    /** Leave a few ticks of steady aim before sending the cast. */
    private static final int AIM_SETTLE_TICKS = 3;
    private static final float AIM_TOLERANCE = 2.5F;

    private final boolean autoRecast;

    private int cooldown;
    private int castConfirmTicks;
    private int reelConfirmTicks;
    private int waterTicks;
    private int dryTicks;
    private int flightTicks;
    private int aimSettledTicks;
    private int caught;
    private double lastHookY = Double.NaN;
    private boolean awaitingCastConfirmation;
    private boolean awaitingReelConfirmation;
    private BlockPos waterTarget;
    private Vec3 castAim;
    private final StatusText status = new StatusText();

    public FishTask(boolean autoRecast) {
        this.autoRecast = autoRecast;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.fish.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Fish");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress learningProgress() {
        return new TaskProgress(caught, autoRecast ? Math.max(1, caught) : 1, Lang.get("lune.unit.catches"));
    }

    @Override
    public LearningContext learningContext(BotContext ctx) {
        return new LearningContext("skill", "fishing",
                ctx.level.dimension().identifier().toString(),
                "mode=" + (autoRecast ? "continuous" : "single-catch"));
    }

    @Override
    public boolean learningCheckpointOnProgress() {
        return autoRecast;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (!ctx.player.getMainHandItem().is(Items.FISHING_ROD)) {
            status.set("lune.status.fish.no_fishing_rod_main_hand");
            return TaskStatus.FAILED;
        }

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        FishingHook hook = ctx.player.fishing;
        if (hook == null) {
            if (awaitingCastConfirmation) {
                if (++castConfirmTicks < CAST_CONFIRM_TIMEOUT) {
                    status.set("lune.status.fish.waiting_bobber", castConfirmTicks, CAST_CONFIRM_TIMEOUT);
                    return TaskStatus.RUNNING;
                }
                status.set("lune.status.fish.cast_did_not_create_bobber");
                return TaskStatus.FAILED;
            }
            if (awaitingReelConfirmation) {
                awaitingReelConfirmation = false;
                reelConfirmTicks = 0;
                resetHookObservation();
                clearAimTarget();
            }
            if (caught > 0 && !autoRecast) {
                status.set("lune.status.fish.caught", caught);
                return TaskStatus.SUCCESS;
            }
            return aimAndCast(ctx);
        }

        if (awaitingReelConfirmation) {
            if (++reelConfirmTicks < REEL_SYNC_TIMEOUT) {
                status.set("lune.status.fish.waiting_reel", reelConfirmTicks, REEL_SYNC_TIMEOUT);
                return TaskStatus.RUNNING;
            }
            // In singleplayer the server can finish retrieve() before the client-side
            // Player.fishing reference receives the removal callback. Do not send another
            // right-click against that stale object; clear only the local reference once,
            // then let the normal cast path create the next bobber.
            ctx.player.fishing = null;
            awaitingReelConfirmation = false;
            reelConfirmTicks = 0;
            resetHookObservation();
            clearAimTarget();
            if (caught > 0 && !autoRecast) {
                status.set("lune.status.fish.caught", caught);
                return TaskStatus.SUCCESS;
            }
            return aimAndCast(ctx);
        }

        awaitingCastConfirmation = false;
        castConfirmTicks = 0;

        if (!hook.isInWater()) {
            if (++flightTicks >= CAST_FLIGHT_TIMEOUT || hook.onGround()) {
                // A bobber that hit land cannot ever produce a bite. Reel it in and let the
                // normal target/aim path send a corrected cast instead of standing still.
                reelRod(ctx);
                awaitingReelConfirmation = true;
                reelConfirmTicks = 0;
                resetHookObservation();
                status.set("lune.status.fish.cast_missed_water_retrying");
                return TaskStatus.RUNNING;
            }
            // A bobber that has already settled is allowed to bob.
            //
            // isInWater() is false on any tick the hook's own box clears the surface, and a float
            // riding the water animation does that constantly - measured, 535 times in a ten-minute
            // run. Resetting the observation on each of those put waterTicks back to zero, and
            // since a bite is only looked for after MIN_WATER_TICKS consecutive ticks in water, the
            // check often never got to run at all: eight fish in ten minutes where vanilla's bite
            // timer alone should give something like thirty.
            //
            // Only a hook that has never been in the water is still in flight, so the grace is
            // conditional on waterTicks - "cast missed water; retrying" is untouched.
            if (waterTicks > 0 && ++dryTicks <= BOB_TOLERANCE) {
                status.set("lune.status.fish.waiting_bite_caught", caught);
                return TaskStatus.RUNNING;
            }
            resetHookObservation();
            status.set("lune.status.fish.waiting_bobber_reach_water_caught", caught);
            return TaskStatus.RUNNING;
        }

        flightTicks = 0;
        dryTicks = 0;
        waterTicks++;
        double downwardDrop = Double.isNaN(lastHookY) ? 0.0 : lastHookY - hook.getY();
        lastHookY = hook.getY();
        if (waterTicks >= MIN_WATER_TICKS
                && (hook.getDeltaMovement().y < BITE_VELOCITY || downwardDrop > BITE_DROP)) {
            reelRod(ctx);
            caught++;
            awaitingReelConfirmation = true;
            reelConfirmTicks = 0;
            resetHookObservation();
            status.set("lune.status.fish.caught", caught);
            return TaskStatus.RUNNING;
        }

        status.set("lune.status.fish.waiting_bite_caught", caught);
        return TaskStatus.RUNNING;
    }

    private void castRod(BotContext ctx) {
        useRod(ctx);
        awaitingCastConfirmation = true;
        castConfirmTicks = 0;
        awaitingReelConfirmation = false;
        reelConfirmTicks = 0;
        resetHookObservation();
        flightTicks = 0;
        aimSettledTicks = 0;
    }

    private void reelRod(BotContext ctx) {
        useRod(ctx);
        awaitingCastConfirmation = false;
        castConfirmTicks = 0;
        reelConfirmTicks = 0;
        flightTicks = 0;
    }

    /** Aim at exposed water and hold that aim briefly before allowing a cast. */
    private TaskStatus aimAndCast(BotContext ctx) {
        if (!isValidWaterTarget(ctx, waterTarget)) {
            waterTarget = findWaterTarget(ctx);
            aimSettledTicks = 0;
            castAim = null;
        }
        if (waterTarget == null) {
            status.set("lune.status.fish.no_stable_water_aim");
            return TaskStatus.FAILED;
        }

        if (castAim == null) {
            castAim = findCastAim(ctx, waterTarget);
        }
        Vec3 aim = castAim;
        ctx.look.lookAt(ctx.player, aim);
        if (!ctx.look.isLookingAt(ctx.player, aim, AIM_TOLERANCE)) {
            aimSettledTicks = 0;
            status.set("lune.status.fish.aiming_water", waterTarget);
            return TaskStatus.RUNNING;
        }
        if (++aimSettledTicks < AIM_SETTLE_TICKS) {
            status.set("lune.status.fish.holding_aim_water", waterTarget);
            return TaskStatus.RUNNING;
        }

        castRod(ctx);
        status.set("lune.status.fish.casting_water", waterTarget);
        return TaskStatus.RUNNING;
    }

    /**
     * Pick the most central visible surface-water block. Only blocks with a clear physical cast
     * path become candidates, then nearby visible water is used to distinguish a pond/river middle
     * from its shoreline. A one-block pool naturally aims at that block's exact horizontal center.
     */
    private BlockPos findWaterTarget(BotContext ctx) {
        BlockPos origin = ctx.player.blockPosition();
        Vec3 eye = ctx.player.getEyePosition();
        List<BlockPos> candidates = new ArrayList<>();

        for (int x = -WATER_SEARCH_RADIUS; x <= WATER_SEARCH_RADIUS; x++) {
            for (int z = -WATER_SEARCH_RADIUS; z <= WATER_SEARCH_RADIUS; z++) {
                for (int y = -WATER_SEARCH_VERTICAL; y <= WATER_SEARCH_VERTICAL; y++) {
                    BlockPos candidate = origin.offset(x, y, z);
                    if (!isSurfaceWater(ctx, candidate)) {
                        continue;
                    }
                    double horizontalSqr = candidate.getX() + 0.5 - ctx.player.getX();
                    double horizontalZ = candidate.getZ() + 0.5 - ctx.player.getZ();
                    double horizontalDistanceSqr = horizontalSqr * horizontalSqr
                            + horizontalZ * horizontalZ;
                    if (horizontalDistanceSqr < MIN_WATER_HORIZONTAL_SQR
                            || !hasClearCastPath(ctx, candidate)) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }

        Set<BlockPos> visibleWater = new HashSet<>(candidates);
        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            int clearance = visibleWaterClearance(candidate, visibleWater);
            int density = visibleWaterDensity(candidate, visibleWater);
            double distanceSqr = eye.distanceToSqr(waterAimPoint(candidate));
            double score = clearance * 10_000.0 + density * 100.0 - distanceSqr
                    - Math.abs(candidate.getY() - origin.getY()) * 2.0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private boolean isValidWaterTarget(BotContext ctx, BlockPos target) {
        return target != null
                && ctx.level.hasChunkAt(target)
                && isSurfaceWater(ctx, target)
                && hasClearCastPath(ctx, target);
    }

    private boolean isSurfaceWater(BotContext ctx, BlockPos pos) {
        if (!ctx.level.hasChunkAt(pos)) {
            return false;
        }
        var fluid = ctx.level.getFluidState(pos);
        return fluid.is(FluidTags.WATER)
                && fluid.isSource()
                && fluid.getFlow(ctx.level, pos).horizontalDistanceSqr() < 0.0001
                && !ctx.level.getFluidState(pos.above()).is(FluidTags.WATER);
    }

    /**
     * Fishing needs a physical cast path rather than a selectable-block outline ray. The collider
     * ray ignores harmless grass and flowers, while solid leaves, walls, and ceilings still block
     * the target exactly as they block the hook in play.
     */
    private boolean hasClearCastPath(BotContext ctx, BlockPos target) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 aim = waterAimPoint(target);
        Vec3 direction = aim.subtract(eye).normalize();
        BlockHitResult hit = ctx.level.clip(new ClipContext(
                eye,
                aim.add(direction.scale(0.2)),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,
                ctx.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    private Vec3 waterAimPoint(BlockPos target) {
        return new Vec3(target.getX() + 0.5, target.getY() + 0.82, target.getZ() + 0.5);
    }

    /**
     * Find a view direction that puts the hook, rather than the player's eyes, over the target.
     * FishingHook launches from a point about 0.3 blocks forward of the eyes and applies gravity
     * and drag immediately. That distinction is negligible for a pond, but it is enough to miss a
     * one-block opening. The small search below mirrors the vanilla launch and flight well enough
     * to choose the pitch that actually crosses the selected water block.
     */
    private Vec3 findCastAim(BotContext ctx, BlockPos target) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 water = waterAimPoint(target);
        double dx = water.x - eye.x;
        double dz = water.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.01) {
            return water;
        }

        Vec3 horizontalUnit = new Vec3(dx / horizontal, 0.0, dz / horizontal);
        Vec3 launch = eye.add(horizontalUnit.scale(0.3));
        double launchDx = water.x - launch.x;
        double launchDz = water.z - launch.z;
        double launchHorizontal = Math.sqrt(launchDx * launchDx + launchDz * launchDz);
        double directPitch = Math.toDegrees(Math.atan2(launch.y - water.y, launchHorizontal));

        double bestPitch = directPitch;
        double bestError = Double.POSITIVE_INFINITY;
        for (int halfDegree = -50; halfDegree <= 50; halfDegree++) {
            double pitch = directPitch + halfDegree * 0.5;
            if (pitch <= -85.0 || pitch >= 85.0) {
                continue;
            }
            if (!trajectoryHitsWater(ctx, target, launch, horizontalUnit, pitch)) {
                continue;
            }
            double error = Math.abs(pitch - directPitch);
            if (error < bestError) {
                bestError = error;
                bestPitch = pitch;
            }
        }

        double radians = Math.toRadians(bestPitch);
        Vec3 direction = new Vec3(
                horizontalUnit.x * Math.cos(radians),
                -Math.sin(radians),
                horizontalUnit.z * Math.cos(radians));
        // Any point along this ray produces the same yaw/pitch. Keeping it away from the player
        // prevents the look controller's near-zero horizontal special case from affecting a short
        // cast.
        return eye.add(direction.scale(10.0));
    }

    private boolean trajectoryHitsWater(BotContext ctx, BlockPos target, Vec3 launch,
                                         Vec3 horizontalUnit, double pitch) {
        double radians = Math.toRadians(pitch);
        double cosine = Math.cos(radians);
        double launchScale = 0.6 * cosine + 0.5;
        Vec3 velocity = new Vec3(
                horizontalUnit.x * launchScale,
                -Math.tan(radians) * launchScale,
                horizontalUnit.z * launchScale);
        Vec3 position = launch;

        for (int tick = 0; tick < 80; tick++) {
            Vec3 next = position.add(velocity);
            BlockHitResult hit = ctx.level.clip(new ClipContext(
                    position,
                    next,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY,
                    ctx.player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                return hit.getBlockPos().equals(target);
            }
            position = next;
            velocity = new Vec3(velocity.x, velocity.y - 0.03, velocity.z).scale(0.92);
        }
        return false;
    }

    private int visibleWaterClearance(BlockPos center, Set<BlockPos> visibleWater) {
        int clearance = 0;
        for (int radius = 1; radius <= CENTER_SAMPLE_RADIUS; radius++) {
            boolean completeRing = true;
            for (int x = -radius; x <= radius && completeRing; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }
                    if (!visibleWater.contains(center.offset(x, 0, z))) {
                        completeRing = false;
                        break;
                    }
                }
            }
            if (!completeRing) {
                break;
            }
            clearance = radius;
        }
        return clearance;
    }

    private int visibleWaterDensity(BlockPos center, Set<BlockPos> visibleWater) {
        int density = 0;
        for (BlockPos candidate : visibleWater) {
            if (candidate.getY() != center.getY()) {
                continue;
            }
            int distance = Math.max(
                    Math.abs(candidate.getX() - center.getX()),
                    Math.abs(candidate.getZ() - center.getZ()));
            if (distance <= CENTER_SAMPLE_RADIUS) {
                density += CENTER_SAMPLE_RADIUS + 1 - distance;
            }
        }
        return density;
    }

    private void resetHookObservation() {
        dryTicks = 0;
        waterTicks = 0;
        lastHookY = Double.NaN;
    }

    private void clearAimTarget() {
        waterTarget = null;
        castAim = null;
        aimSettledTicks = 0;
    }

    private void useRod(BotContext ctx) {
        ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
        ctx.player.swing(InteractionHand.MAIN_HAND);
        cooldown = USE_COOLDOWN;
    }
}
