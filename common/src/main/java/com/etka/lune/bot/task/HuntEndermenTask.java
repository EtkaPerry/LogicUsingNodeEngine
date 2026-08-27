package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * Roams the surface looking for Endermen and collects their pearls.
 */
public final class HuntEndermenTask implements Task {

    private static final int HUNT_RADIUS = 64;
    private static final int LOOT_RADIUS = 32;
    private static final int ROAM_DISTANCE = 80;
    /** Endermen only drop pearls half the time; twelve attempts cannot supply fourteen pearls. */
    private static final int MAX_ROAMS = 48;

    private enum State {
        FIND, HUNT, LOOT, ROAM
    }

    private final int wanted;
    private final KillOptions options;

    private int startPearls;
    private State state = State.FIND;
    private Task current;
    private int roams;
    private int gatheredPearls;
    /** Survives the KillTask rebuild each hunt cycle; see {@link KillTask} for why that matters. */
    private final java.util.Set<Integer> unreachableMobs;
    private final boolean clearUnreachableOnStart;
    private String status = "";

    public HuntEndermenTask(int wanted) {
        this(wanted, new KillOptions(false, true, false,
                KillOptions.EndermanSafety.AUTO,
                KillOptions.WeaponPreference.SWORD, false));
    }

    public HuntEndermenTask(int wanted, KillOptions options) {
        this(wanted, options, null);
    }

    /** Retain failed mob ids when SpeedrunTask rebuilds the hunt after a bounded retry. */
    public HuntEndermenTask(int wanted, KillOptions options,
                            java.util.Set<Integer> sharedUnreachableMobs) {
        this.wanted = Math.max(1, wanted);
        this.options = options == null ? KillOptions.basic() : options;
        this.unreachableMobs = sharedUnreachableMobs == null
                ? new java.util.HashSet<>() : sharedUnreachableMobs;
        this.clearUnreachableOnStart = sharedUnreachableMobs == null;
    }

    @Override
    public String name() {
        return "Hunt Endermen";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(gatheredPearls, wanted, "pearls");
    }

    @Override
    public void onStart(BotContext ctx) {
        startPearls = InventoryHelper.count(ctx.player, Items.ENDER_PEARL);
        state = State.FIND;
        current = null;
        roams = 0;
        gatheredPearls = 0;
        if (clearUnreachableOnStart) {
            unreachableMobs.clear();
        }
        publishHuntDebug(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        publishHuntDebug(ctx);
        int pearls = InventoryHelper.count(ctx.player, Items.ENDER_PEARL);
        gatheredPearls = Math.max(0, pearls - startPearls);
        if (pearls - startPearls >= wanted) {
            if (current != null) {
                current.stop(ctx);
                current = null;
            }
            status = "gathered " + (pearls - startPearls) + " pearls";
            return TaskStatus.SUCCESS;
        }

        if (current != null) {
            TaskStatus r = current.tick(ctx);
            status = state + " - " + current.status();
            if (r == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            current.stop(ctx);
            current = null;
            return advance(ctx, r);
        }

        return advance(ctx, TaskStatus.SUCCESS);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
        ctx.input.reset();
    }

    private TaskStatus advance(BotContext ctx, TaskStatus previous) {
        switch (state) {
            case FIND -> {
                if (LootTask.hasDropsNearby(ctx, LOOT_RADIUS)) {
                    state = State.LOOT;
                } else if (roams < MAX_ROAMS) {
                    state = State.ROAM;
                } else {
                    status = "roamed too much (" + roams + "/" + MAX_ROAMS + "), only "
                            + (InventoryHelper.count(ctx.player, Items.ENDER_PEARL) - startPearls)
                            + " pearls";
                    return TaskStatus.FAILED;
                }
            }
            case HUNT -> {
                state = previous == TaskStatus.FAILED ? State.ROAM : State.LOOT;
            }
            case LOOT -> {
                state = State.ROAM;
                roams++;
            }
            case ROAM -> {
                state = State.HUNT;
            }
        }

        current = createTask(ctx);
        if (current == null) {
            status = state + " failed";
            return TaskStatus.FAILED;
        }
        current.start(ctx);
        status = state + " started";
        return TaskStatus.RUNNING;
    }

    private Task createTask(BotContext ctx) {
        return switch (state) {
            case HUNT -> new KillTask(Set.of(EntityType.ENDERMAN), HUNT_RADIUS, options, unreachableMobs);
            case LOOT -> new LootTask(LOOT_RADIUS);
            case ROAM -> new GotoTask(new Goals.Near(pickRoamTarget(ctx), 8), true, false);
            default -> null;
        };
    }

    private void publishHuntDebug(BotContext ctx) {
        ctx.debug.intent = "hunting visible endermen for pearls";
        ctx.debug.searchAttempt = roams;
        ctx.debug.searchLimit = MAX_ROAMS;
        ctx.debug.giveUp = "pearl hunt roams " + roams + "/" + MAX_ROAMS;
        ctx.debug.memory = unreachableMobs.size() + " unreachable endermen remembered";
    }

    private BlockPos pickRoamTarget(BotContext ctx) {
        BlockPos p = ctx.player.blockPosition();
        RandomSource random = ctx.player.getRandom();
        int dx = Mth.randomBetweenInclusive(random, -ROAM_DISTANCE, ROAM_DISTANCE);
        int dz = Mth.randomBetweenInclusive(random, -ROAM_DISTANCE, ROAM_DISTANCE);
        return new BlockPos(p.getX() + dx, p.getY(), p.getZ() + dz);
    }
}
