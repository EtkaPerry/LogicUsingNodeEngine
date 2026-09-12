package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Throws and follows an Eye of Ender until it lands, returning the approximate stronghold location.
 */
public final class EnderEyeTask implements Task {

    private static final double SCAN_RADIUS = 64.0;
    private static final double LAND_RANGE = 10.0;
    private static final double LAND_RANGE_SQR = LAND_RANGE * LAND_RANGE;
    private static final int WATCH_TICKS = 8;
    private static final int SPAWN_TIMEOUT = 20;
    private static final int RETHROW_DISTANCE = 120;
    private static final int RETHROW_DISTANCE_SQR = RETHROW_DISTANCE * RETHROW_DISTANCE;

    private enum State {
        THROW, TRACK, WALK
    }

    private State state = State.THROW;
    private EyeOfEnder eye;
    private Vec3 heading = Vec3.ZERO;
    private Vec3 lastEyePos;
    private Vec3 lastEyeMotion;
    private BlockPos stronghold;
    private BlockPos lastThrowPos;
    private int watchTicks;
    private int spawnWait;
    private int walkTicks;
    private final StatusText status = new StatusText();

    @Override
    public String name() {
        return Lang.get("lune.task.ender_eye.find_stronghold");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Find Stronghold");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        state = State.THROW;
        eye = null;
        heading = Vec3.ZERO;
        watchTicks = 0;
        spawnWait = 0;
        walkTicks = 0;
        stronghold = null;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (stronghold != null) {
            status.set("lune.status.ender_eye.stronghold_near", stronghold.getX(), stronghold.getZ());
            return TaskStatus.SUCCESS;
        }

        if (!InventoryHelper.has(ctx.player, Items.ENDER_EYE, 1)) {
            status.set("lune.status.ender_eye.out_eyes_ender");
            return TaskStatus.FAILED;
        }

        return switch (state) {
            case THROW -> throwEye(ctx);
            case TRACK -> track(ctx);
            case WALK -> walk(ctx);
        };
    }

    @Override
    public void onStop(BotContext ctx) {
        ctx.input.reset();
    }

    public BlockPos strongholdPos() {
        return stronghold;
    }

    private TaskStatus throwEye(BotContext ctx) {
        if (InventoryHelper.equip(ctx, stack -> stack.is(Items.ENDER_EYE)) < 0) {
            status.set("lune.status.ender_eye.no_eyes_ender");
            return TaskStatus.FAILED;
        }

        ctx.gameMode.useItem(ctx.player, InteractionHand.MAIN_HAND);
        ctx.player.swing(InteractionHand.MAIN_HAND);

        lastThrowPos = ctx.player.blockPosition();
        eye = null;
        lastEyePos = null;
        lastEyeMotion = null;
        watchTicks = 0;
        spawnWait = 0;
        state = State.TRACK;
        status.set("lune.status.ender_eye.threw_eye");
        return TaskStatus.RUNNING;
    }

    private TaskStatus track(BotContext ctx) {
        eye = findEye(ctx);

        if (eye == null) {
            spawnWait++;
            status.set("lune.status.ender_eye.waiting_eye", spawnWait);
            if (spawnWait >= SPAWN_TIMEOUT) {
                state = State.THROW;
            }
            return TaskStatus.RUNNING;
        }

        Vec3 motion = eye.getDeltaMovement();
        lastEyePos = eye.position();
        lastEyeMotion = motion;

        if (closeAndFalling(ctx.player.position(), eye.position(), motion)) {
            return land(ctx, eye.blockPosition());
        }

        watchTicks++;
        status.set("lune.status.ender_eye.tracking_eye");

        if (watchTicks >= WATCH_TICKS) {
            Vec3 h = new Vec3(motion.x, 0, motion.z);
            if (h.lengthSqr() < 1.0E-6 && lastEyePos != null) {
                h = lastEyePos.subtract(ctx.player.position());
                h = new Vec3(h.x, 0, h.z);
            }
            if (h.lengthSqr() > 1.0E-6) {
                heading = h.normalize();
            }
            state = State.WALK;
            walkTicks = 0;
            status.set("lune.status.ender_eye.heading", (int) heading.x, (int) heading.z);
        }

        return TaskStatus.RUNNING;
    }

    private TaskStatus walk(BotContext ctx) {
        eye = findEye(ctx);
        walkTicks++;

        if (eye != null) {
            lastEyePos = eye.position();
            lastEyeMotion = eye.getDeltaMovement();

            if (closeAndFalling(ctx.player.position(), eye.position(), eye.getDeltaMovement())) {
                return land(ctx, eye.blockPosition());
            }
        }

        ItemEntity drop = findDrop(ctx);
        if (drop != null) {
            return land(ctx, drop.blockPosition());
        }

        if (eye == null) {
            if (lastEyePos != null && lastEyeMotion != null
                    && lastEyePos.distanceToSqr(ctx.player.position()) <= LAND_RANGE_SQR
                    && lastEyeMotion.y < -0.05) {
                return land(ctx, BlockPos.containing(lastEyePos));
            }

            if (lastThrowPos != null
                    && ctx.player.position().distanceToSqr(Vec3.atCenterOf(lastThrowPos)) > RETHROW_DISTANCE_SQR
                    && walkTicks > 20) {
                state = State.THROW;
                status.set("lune.status.ender_eye.rethrowing");
                return TaskStatus.RUNNING;
            }

            if (walkTicks > 300) {
                state = State.THROW;
                status.set("lune.status.ender_eye.rethrowing");
                return TaskStatus.RUNNING;
            }
        }

        if (heading.lengthSqr() < 1.0E-6) {
            state = State.THROW;
            status.set("lune.status.ender_eye.lost_heading_rethrowing");
            return TaskStatus.RUNNING;
        }

        Vec3 target = ctx.player.position().add(heading.scale(5.0));
        ctx.look.lookAt(ctx.player, target);
        ctx.input.forward = true;
        ctx.input.sprint = true;
        status.set("lune.status.ender_eye.following_eye");
        return TaskStatus.RUNNING;
    }

    private EyeOfEnder findEye(BotContext ctx) {
        AABB box = ctx.player.getBoundingBox().inflate(SCAN_RADIUS);
        List<EyeOfEnder> found = ctx.level.getEntities(EntityTypeTest.forClass(EyeOfEnder.class), box, e -> true);
        return found.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player)))
                .orElse(null);
    }

    private ItemEntity findDrop(BotContext ctx) {
        AABB box = ctx.player.getBoundingBox().inflate(LAND_RANGE);
        List<ItemEntity> found = ctx.level.getEntities(EntityTypeTest.forClass(ItemEntity.class), box,
                e -> e.getItem().is(Items.ENDER_EYE));
        return found.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(ctx.player)))
                .orElse(null);
    }

    private boolean closeAndFalling(Vec3 player, Vec3 eye, Vec3 motion) {
        return eye.distanceToSqr(player) <= LAND_RANGE_SQR && motion.y < -0.05;
    }

    private TaskStatus land(BotContext ctx, BlockPos pos) {
        stronghold = pos;
        ctx.input.reset();
        status.set("lune.status.ender_eye.eye_landed", pos.getX(), pos.getY(), pos.getZ());
        return TaskStatus.SUCCESS;
    }
}
