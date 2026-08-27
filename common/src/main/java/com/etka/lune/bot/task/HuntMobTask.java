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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Roams, hunts one mob type, and collects its drops until the requested amount is reached.
 *
 * <p>This is deliberately separate from {@link KillTask}: Kill remains the general nearby-entity
 * command, while the specialised Hunt commands can roam and prepare the right combat behaviour for
 * one enemy family.</p>
 */
public class HuntMobTask implements Task {

    private static final int HUNT_RADIUS = 64;
    private static final int LOOT_RADIUS = 32;
    private static final int ROAM_DISTANCE = 80;
    private static final int MAX_ROAMS = 12;

    private enum State {
        FIND, HUNT, LOOT, ROAM
    }

    private final EntityType<?> targetType;
    private final Predicate<ItemStack> dropMatches;
    private final String dropLabel;
    private final String label;
    private final int wanted;
    private final KillOptions options;

    /**
     * Mobs already written off, kept out here because the hunt builds a fresh {@link KillTask}
     * every time it cycles back to hunting. Held inside Kill it would be wiped each cycle, and the
     * hunt would walk back to the same unreachable animal for as long as the routine ran.
     */
    private final java.util.Set<Integer> unreachableMobs;
    private final boolean clearUnreachableOnStart;

    private int startDrops;
    private State state = State.FIND;
    private Task current;
    private int roams;
    private int gainedDrops;
    private String status = "";

    public HuntMobTask(EntityType<?> targetType, Item drop, String label, int wanted,
                       KillOptions options) {
        this(targetType, stack -> stack.is(drop), InventoryHelper.itemName(drop),
                label, wanted, options, null);
    }

    public HuntMobTask(EntityType<?> targetType, Predicate<ItemStack> dropMatches,
                       String dropLabel, String label, int wanted, KillOptions options) {
        this(targetType, dropMatches, dropLabel, label, wanted, options, null);
    }

    /**
     * Lets a long-running caller retain failed mob ids when it rebuilds this hunt after a retry.
     * A fresh hunt keeps the old behaviour; only an explicit shared set survives the rebuild.
     */
    public HuntMobTask(EntityType<?> targetType, Predicate<ItemStack> dropMatches,
                       String dropLabel, String label, int wanted, KillOptions options,
                       java.util.Set<Integer> sharedUnreachableMobs) {
        this.targetType = targetType;
        this.dropMatches = dropMatches;
        this.dropLabel = dropLabel;
        this.label = label;
        this.wanted = Math.max(1, wanted);
        this.options = options == null ? KillOptions.basic() : options;
        this.unreachableMobs = sharedUnreachableMobs == null
                ? new java.util.HashSet<>() : sharedUnreachableMobs;
        this.clearUnreachableOnStart = sharedUnreachableMobs == null;
    }

    @Override
    public String name() {
        return "Hunt " + label;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(gainedDrops, wanted, dropLabel);
    }

    @Override
    public void onStart(BotContext ctx) {
        startDrops = InventoryHelper.count(ctx.player, dropMatches);
        state = State.FIND;
        current = null;
        roams = 0;
        gainedDrops = 0;
        if (clearUnreachableOnStart) {
            unreachableMobs.clear();
        }
        status = "";
        publishHuntDebug(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        publishHuntDebug(ctx);
        int gained = gained(ctx);
        gainedDrops = Math.max(0, gained);
        if (gained >= wanted) {
            stopCurrent(ctx);
            status = "gathered " + gained + " " + dropLabel;
            return TaskStatus.SUCCESS;
        }

        if (current != null) {
            TaskStatus result = current.tick(ctx);
            status = state + " - " + current.status();
            if (result == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            stopCurrent(ctx);
            return advance(ctx, result);
        }

        return advance(ctx, TaskStatus.SUCCESS);
    }

    @Override
    public void onStop(BotContext ctx) {
        stopCurrent(ctx);
        ctx.input.reset();
    }

    private TaskStatus advance(BotContext ctx, TaskStatus previous) {
        switch (state) {
            case FIND -> {
                if (LootTask.hasDropsNearby(ctx, LOOT_RADIUS)) {
                    state = State.LOOT;
                } else if (roams < maxRoams()) {
                    state = State.ROAM;
                } else {
                    status = "roamed too much (" + roams + "/" + maxRoams()
                            + "), only " + gained(ctx) + " " + dropLabel;
                    return TaskStatus.FAILED;
                }
            }
            case HUNT -> state = previous == TaskStatus.FAILED ? State.ROAM : State.LOOT;
            case LOOT -> {
                state = State.ROAM;
                roams++;
            }
            case ROAM -> state = State.HUNT;
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
            case HUNT -> new KillTask(Set.of(targetType), HUNT_RADIUS, options, unreachableMobs);
            case LOOT -> new LootTask(LOOT_RADIUS);
            case ROAM -> new GotoTask(new Goals.Near(pickRoamTarget(ctx), 8), true, false);
            default -> null;
        };
    }

    /** Specialized hunters may need a larger, still bounded, search budget. */
    protected int maxRoams() {
        return MAX_ROAMS;
    }

    private void publishHuntDebug(BotContext ctx) {
        ctx.debug.searchAttempt = roams;
        ctx.debug.searchLimit = maxRoams();
        ctx.debug.giveUp = "hunt roams " + roams + "/" + maxRoams();
        ctx.debug.memory = unreachableMobs.size() + " unreachable "
                + targetType.getDescription().getString() + " ids remembered";
    }

    private int gained(BotContext ctx) {
        return InventoryHelper.count(ctx.player, dropMatches) - startDrops;
    }

    private void stopCurrent(BotContext ctx) {
        if (current != null) {
            current.stop(ctx);
            current = null;
        }
    }

    private BlockPos pickRoamTarget(BotContext ctx) {
        BlockPos p = ctx.player.blockPosition();
        RandomSource random = ctx.player.getRandom();
        int dx = Mth.randomBetweenInclusive(random, -ROAM_DISTANCE, ROAM_DISTANCE);
        int dz = Mth.randomBetweenInclusive(random, -ROAM_DISTANCE, ROAM_DISTANCE);
        return new BlockPos(p.getX() + dx, p.getY(), p.getZ() + dz);
    }
}
