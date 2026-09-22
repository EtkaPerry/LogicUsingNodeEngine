package com.etka.lune.bot.task;

import com.etka.lune.compat.Screens;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.ItemFilters;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finds a nearby chest or barrel, walks to it, and shift-clicks matching items out of the player's
 * inventory.
 * <p>
 * The filters are the shared ones in {@link ItemFilters}, deliberately broad so a long mining or
 * harvesting run can be followed by a single deposit step - and the same ones a backpack takes.
 */
public final class DepositTask implements Task {

    private static final int SEARCH_RADIUS = 16;
    private static final int ACTION_COOLDOWN = 5;
    private static final int MAX_OPEN_ATTEMPTS = 8;
    /** Squared vanilla interaction distance - the same number {@code SmeltTask} interacts within. */
    private static final double INTERACT_REACH_SQR = 20.0;

    private static final Set<Block> CONTAINERS = Set.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);

    private final String filter;
    private final int radius;
    private final boolean optional;

    /** Containers this card has tried and written off, so the next scan skips them. */
    private final Set<Long> refused = new HashSet<>();
    private BlockPos target;
    private GotoTask approach;
    private int cooldown;
    private int openAttempts;
    private int aimTicks;
    /** Whether the approach to the current target has already been tightened to its own sides. */
    private boolean tightened;
    /** Why the last container was written off, for when there is no other one left to try. */
    private String refusal = "lune.status.deposit.cannot_open_container";
    private final StatusText status = new StatusText();

    public DepositTask(String filter, int radius) {
        this(filter, radius, false);
    }

    public DepositTask(String filter, int radius, boolean optional) {
        this.filter = filter == null || filter.isBlank() ? "all" : filter;
        this.radius = Math.max(1, radius);
        this.optional = optional;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.deposit.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Deposit");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        Predicate<ItemStack> matcher = makeMatcher(ctx);

        // Asked before a container is looked for, because finding one is a block scan, a path and
        // a walk - all of it bought before the first shift-click can show there was nothing to
        // move. A measured run of the Deposit coverage task put its one stack of stone away at
        // tick 887 and then made seven more full round trips to the same chest carrying nothing
        // that matched: 1028 ticks aiming at it and 868 walking to it, ninety-five seconds of a
        // seven-hundred-second run spent depositing air.
        //
        // This is a success rather than a skip, and it does not consult `optional`: a card with
        // nothing to put away has done its job, while `optional` answers a different question -
        // whether a container the world failed to provide is allowed to stop the graph.
        if (!isContainerOpen(ctx) && !DepositPolicy.holdsMatching(ctx.player, matcher)) {
            status.set("lune.status.deposit.nothing_to_deposit", filter);
            return TaskStatus.SUCCESS;
        }

        if (target != null && !CONTAINERS.contains(ctx.level.getBlockState(target).getBlock())) {
            target = null;
            tightened = false;
            aimTicks = 0;
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
        }

        if (target == null) {
            target = BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                    CONTAINERS, radius, ctx.level.getMinY(), ctx.level.getMaxY(), refused);
            if (target == null) {
                // "There was never one" and "I tried them and none of them worked" are different
                // sentences, and the second is the useful one when a chest is standing right there.
                return refused.isEmpty()
                        ? unavailable("lune.status.deposit.no_container_within", radius)
                        : unavailable(refusal);
            }
        }

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        if (!isContainerOpen(ctx)) {
            if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                return writeOff(ctx, "lune.status.deposit.cannot_open_container");
            }
            return openContainer(ctx);
        }

        // Deposit one matching stack per tick, then wait for the server to move it.
        AbstractContainerMenu menu = ctx.player.containerMenu;
        for (Slot slot : menu.slots) {
            if (slot.container != ctx.player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            // Read what is being moved before moving it. QUICK_MOVE empties the slot in the same
            // call, and `stack` is the slot's own ItemStack rather than a copy - so asking it
            // afterwards describes the hole it left: every measured deposit reported
            // "depositing 0 Air" while correctly moving a full stack of stone.
            int moving = stack.getCount();
            String moved = stack.getHoverName().getString();
            ctx.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, ctx.player);
            cooldown = ACTION_COOLDOWN;
            status.set("lune.status.deposit.depositing", moving, moved);
            return TaskStatus.RUNNING;
        }

        closeMenu(ctx);
        status.set("lune.status.deposit.deposited", filter);
        return TaskStatus.SUCCESS;
    }

    private TaskStatus openContainer(BotContext ctx) {
        if (inReach(ctx, target)) {
            if (BlockPlacer.use(ctx, target)) {
                openAttempts++;
                aimTicks = 0;
                status.set("lune.status.deposit.opening_container");
                cooldown = ACTION_COOLDOWN + 2;
                return TaskStatus.RUNNING;
            }
            if (++aimTicks >= DepositPolicy.MAX_AIM_TICKS) {
                return writeOff(ctx, "lune.status.deposit.cannot_open_container");
            }
            status.set("lune.status.deposit.aiming_container");
            return TaskStatus.RUNNING;
        }

        aimTicks = 0;
        if (approach == null) {
            approach = new GotoTask(DepositPolicy.approachGoal(target, tightened), false, true);
            approach.start(ctx);
        }
        TaskStatus walk = approach.tick(ctx);
        if (walk == TaskStatus.SUCCESS) {
            approach.stop(ctx);
            approach = null;
            if (inReach(ctx, target)) {
                return TaskStatus.RUNNING;
            }
            // The route says it arrived and the container still cannot be clicked, so the bot is
            // standing beside it with something in the way. Rebuilding the same satisfied goal
            // would return success from a zero-node search every tick - a standstill wearing a
            // progress bar - so tighten the goal once to the blocks that actually border the
            // container, and write the container off if that lands no better.
            if (!tightened) {
                tightened = true;
                status.set("lune.status.deposit.beside_blocked_container");
                return TaskStatus.RUNNING;
            }
            return writeOff(ctx, "lune.status.deposit.cannot_reach_container");
        }
        if (walk == TaskStatus.FAILED) {
            approach.stop(ctx);
            approach = null;
            return writeOff(ctx, "lune.status.deposit.cannot_reach_container");
        }
        status.set("lune.status.deposit.walking_container");
        return TaskStatus.RUNNING;
    }

    /**
     * Writes the current container off for the rest of this card and goes looking for another.
     *
     * <p>{@code reason} is kept rather than reported, because one unusable chest is not the end
     * of the job - there may be a barrel behind it. It becomes the card's own answer only when
     * the scan runs out of containers that have not already been tried.</p>
     */
    private TaskStatus writeOff(BotContext ctx, String reason) {
        refused.add(target.asLong());
        refusal = reason;
        target = null;
        tightened = false;
        aimTicks = 0;
        openAttempts = 0;
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        status.set("lune.status.deposit.container_blocked_trying_another");
        return TaskStatus.RUNNING;
    }

    private TaskStatus unavailable(String key, Object... args) {
        if (optional) {
            status.set("lune.status.deposit.skipping_optional",
                    new StatusText().set(key, args));
            return TaskStatus.SUCCESS;
        }
        status.set(key, args);
        return TaskStatus.FAILED;
    }

    private boolean isContainerOpen(BotContext ctx) {
        return ctx.player.containerMenu != null && ctx.player.containerMenu != ctx.player.inventoryMenu;
    }

    private static boolean inReach(BotContext ctx, BlockPos pos) {
        if (ctx.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > INTERACT_REACH_SQR) {
            return false;
        }
        // Distance alone is not an interaction position, and this card was the last one still
        // believing it was. A bot standing the far side of the wall a chest is set into, or one
        // block under a barrel with a lip in the way, is inside the vanilla reach radius and
        // cannot click any face - so BlockPlacer.use refused every tick, forever, while the card
        // reported that it was aiming. One measured run lost 4301 ticks that way, 31% of its
        // whole budget, to a single chest, with "cannot reach Chest; something is in the way" in
        // the journal 4500 ticks running.
        //
        // CraftingTableAccess and SmeltTask already ask this question, in the same words, for the
        // same reason. Asking it here keeps the approach goal alive until the container's own
        // centre is the first block the player's ray hits.
        return BlockPlacer.hasLineOfSight(ctx, pos);
    }

    private void closeMenu(BotContext ctx) {
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            Screens.open(ctx.mc, null);
        }
    }

    private Predicate<ItemStack> makeMatcher(BotContext ctx) {
        return ItemFilters.matcher(filter, null);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        closeMenu(ctx);
        ctx.input.reset();
    }
}
