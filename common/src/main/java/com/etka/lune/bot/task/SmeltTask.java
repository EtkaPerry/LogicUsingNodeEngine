package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Uses a furnace to smelt a chosen input into its output. Finds or places a furnace, keeps it fuelled
 * and fed, and waits for the result.
 * <p>
 * This is the missing link for the iron/gold/copper tool chain: the bot mines ore, gets raw metal, then
 * this task turns it into ingots.
 */
public final class SmeltTask implements Task {

    private static final int SEARCH_RADIUS = 8;
    private static final int MAX_OPEN_ATTEMPTS = 4;
    private static final int ACTION_COOLDOWN = 5;
    private static final int SMELT_TICKS = 200;
    private static final double INTERACT_REACH_SQR = 20.0;
    private static final int MAX_PLACE_TICKS = 40;
    private static final int MAX_PLACEMENT_SPOTS = 4;

    private final Set<Item> inputs;
    private final Item output;
    private final int wanted;

    private BlockPos furnacePos;
    /** A candidate is kept while the bot turns and waits for the server to accept placement. */
    private BlockPos placingFurnacePos;
    private int furnacePlaceTicks;
    private int furnacePlacementAttempts;
    private final Set<Long> badPlacementSpots = new java.util.HashSet<>();
    /** A cramped mining tunnel may have no legal two-block-high furnace cell. */
    private SurfaceRecoveryTask surfaceRecovery;
    private boolean surfaceRecoveryAttempted;
    private GotoTask approach;
    private int openAttempts;
    private int cooldown;
    private int startCount = -1;
    private int completed;
    private final StatusText status = new StatusText();

    public SmeltTask(Set<Item> inputs, Item output, int wanted) {
        this.inputs = Set.copyOf(inputs);
        this.output = output;
        this.wanted = Math.max(1, wanted);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.smelt.name", InventoryHelper.itemName(output));
    }

    /** English on purpose: this is the learner's row key, and is never shown. */
    @Override
    public String learningId() {
        return Task.learningName("Smelt " + InventoryHelper.itemName(output));
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(completed, wanted, Lang.get("lune.unit.items"));
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        int have = InventoryHelper.count(ctx.player, output);
        if (startCount < 0) {
            startCount = have;
        }

        int done = have - startCount;
        completed = Math.max(0, done);
        if (done >= wanted) {
            closeMenu(ctx);
            status.set("lune.status.smelt.smelted_x", InventoryHelper.itemName(output), done);
            return TaskStatus.SUCCESS;
        }

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        if (surfaceRecovery != null) {
            TaskStatus recovery = surfaceRecovery.tick(ctx);
            status.set("lune.status.smelt.returning_dry_ground", surfaceRecovery.statusLine());
            if (recovery == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }
            surfaceRecovery.stop(ctx);
            surfaceRecovery = null;
            if (recovery == TaskStatus.SUCCESS) {
                cooldown = ACTION_COOLDOWN;
                status.set("lune.status.smelt.reached_room_furnace");
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.smelt.could_not_make_room_furnace");
            return TaskStatus.FAILED;
        }

        // Placement needs several ticks: BlockPlacer first turns the head toward the support face,
        // then sends the interaction, and only after the server update does the block exist in the
        // client level. Keep the spot across ticks just like CraftTask does for crafting tables;
        // failing immediately here loses the only furnace on the first aiming tick.
        if (placingFurnacePos != null) {
            if (ctx.level.getBlockState(placingFurnacePos).is(Blocks.FURNACE)) {
                furnacePos = placingFurnacePos;
                placingFurnacePos = null;
                furnacePlaceTicks = 0;
                furnacePlacementAttempts = 0;
                openAttempts = 0;
                status.set("lune.status.smelt.furnace_placed");
                cooldown = ACTION_COOLDOWN;
                return TaskStatus.RUNNING;
            }
            if (++furnacePlaceTicks <= MAX_PLACE_TICKS) {
                BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(
                        ctx, Blocks.FURNACE, placingFurnacePos);
                if (!placement.isTransient()) {
                    badPlacementSpots.add(placingFurnacePos.asLong());
                    placingFurnacePos = null;
                    furnacePlaceTicks = 0;
                    if (++furnacePlacementAttempts < MAX_PLACEMENT_SPOTS) {
                        status.set("lune.status.smelt.furnace_spot_failed_trying_another", placement.displayName());
                        return TaskStatus.RUNNING;
                    }
                    furnacePlacementAttempts = 0;
                    status.set("lune.status.smelt.could_not_place_furnace_2", placement.displayName());
                    return TaskStatus.FAILED;
                }
                status.set("lune.status.smelt.placing_furnace");
                return TaskStatus.RUNNING;
            }
            badPlacementSpots.add(placingFurnacePos.asLong());
            placingFurnacePos = null;
            furnacePlaceTicks = 0;
            if (++furnacePlacementAttempts < MAX_PLACEMENT_SPOTS) {
                status.set("lune.status.smelt.furnace_spot_failed_trying_another_2");
                return TaskStatus.RUNNING;
            }
            furnacePlacementAttempts = 0;
            status.set("lune.status.smelt.could_not_place_furnace");
            return TaskStatus.FAILED;
        }

        // A crafting-table screen can survive the child-task handoff for one client tick, and
        // it must never be allowed to block the furnace task.  Close any stale non-furnace menu
        // before trying to walk or interact; otherwise the player can stand in front of a valid
        // furnace forever while the old 3x3 screen is still active.
        if (!(ctx.player.containerMenu instanceof AbstractFurnaceMenu)
                && ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            ctx.mc.setScreen(null);
        }

        if (furnacePos == null || !ctx.level.getBlockState(furnacePos).is(Blocks.FURNACE)) {
            furnacePos = findFurnace(ctx);
            if (furnacePos == null) {
                if (!InventoryHelper.has(ctx.player, Items.FURNACE, 1)) {
                    status.set("lune.status.smelt.no_furnace");
                    return TaskStatus.FAILED;
                }
                placingFurnacePos = BlockPlacer.findPlacementSpot(ctx, badPlacementSpots);
                furnacePlaceTicks = 0;
                if (placingFurnacePos == null) {
                    if (!surfaceRecoveryAttempted) {
                        surfaceRecoveryAttempted = true;
                        surfaceRecovery = new SurfaceRecoveryTask();
                        surfaceRecovery.start(ctx);
                        status.set("lune.status.smelt.no_room_furnace_returning_dry_ground");
                        return TaskStatus.RUNNING;
                    }
                    status.set("lune.status.smelt.nowhere_put_furnace_after_surface");
                    return TaskStatus.FAILED;
                }
                status.set("lune.status.smelt.placing_furnace");
                return TaskStatus.RUNNING;
            }
            badPlacementSpots.clear();
            furnacePlacementAttempts = 0;
            openAttempts = 0;
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
        }

        if (!(ctx.player.containerMenu instanceof AbstractFurnaceMenu menu)) {
            if (inReach(ctx, furnacePos)) {
                if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                    status.set("lune.status.smelt.cant_open_furnace");
                    return TaskStatus.FAILED;
                }
                if (BlockPlacer.use(ctx, furnacePos)) {
                    openAttempts++;
                    status.set("lune.status.smelt.opening_furnace");
                    cooldown = ACTION_COOLDOWN + 3;
                } else {
                    status.set("lune.status.smelt.aiming_furnace");
                }
                return TaskStatus.RUNNING;
            }

            if (approach == null) {
                approach = new GotoTask(new Goals.Adjacent(furnacePos, 3.5), false, true);
                approach.start(ctx);
            }
            TaskStatus walk = approach.tick(ctx);
            if (walk == TaskStatus.FAILED) {
                status.set("lune.status.smelt.cant_reach_furnace");
                return TaskStatus.FAILED;
            }
            status.set("lune.status.smelt.walking_furnace");
            return TaskStatus.RUNNING;
        }

        int remaining = wanted - done;

        // Collect finished output first so the slot never backs up.
        int resultSlot = AbstractFurnaceMenu.RESULT_SLOT;
        ItemStack result = menu.getSlot(resultSlot).getItem();
        if (!result.isEmpty() && result.is(output)) {
            ctx.gameMode.handleContainerInput(menu.containerId, resultSlot, 0, ContainerInput.QUICK_MOVE, ctx.player);
            cooldown = ACTION_COOLDOWN;
            status.set("lune.status.smelt.collecting", InventoryHelper.itemName(output));
            return TaskStatus.RUNNING;
        }

        int fuelSlot = AbstractFurnaceMenu.FUEL_SLOT;
        ItemStack fuel = menu.getSlot(fuelSlot).getItem();
        if (fuel.isEmpty()) {
            int fuelInvSlot = findBestFuelSlot(ctx, menu, remaining);
            if (fuelInvSlot >= 0) {
                ctx.gameMode.handleContainerInput(menu.containerId, fuelInvSlot, 0, ContainerInput.QUICK_MOVE, ctx.player);
                cooldown = ACTION_COOLDOWN;
                status.set("lune.status.smelt.adding_fuel");
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.smelt.no_fuel");
            return TaskStatus.FAILED;
        }

        int inputSlot = AbstractFurnaceMenu.INGREDIENT_SLOT;
        ItemStack input = menu.getSlot(inputSlot).getItem();
        if (input.isEmpty() || input.getCount() < remaining) {
            int inputInvSlot = findInputSlot(ctx, menu);
            if (inputInvSlot >= 0) {
                ctx.gameMode.handleContainerInput(menu.containerId, inputInvSlot, 0, ContainerInput.QUICK_MOVE, ctx.player);
                cooldown = ACTION_COOLDOWN;
                status.set("lune.status.smelt.adding", menu.getSlot(inputInvSlot).getItem().getHoverName().getString());
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.smelt.no", InventoryHelper.itemName(inputs.iterator().next()));
            return TaskStatus.FAILED;
        }

        status.set("lune.status.smelt.smelting", InventoryHelper.itemName(output), done, wanted);
        return TaskStatus.RUNNING;
    }

    private int findInputSlot(BotContext ctx, AbstractFurnaceMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container == ctx.player.getInventory()) {
                ItemStack stack = slot.getItem();
                if (inputs.contains(stack.getItem()) && !stack.isEmpty()) {
                    return slot.index;
                }
            }
        }
        return -1;
    }

    private int findBestFuelSlot(BotContext ctx, AbstractFurnaceMenu menu, int remainingItems) {
        int needTicks = remainingItems * SMELT_TICKS;
        List<FuelSlot> candidates = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container == ctx.player.getInventory()) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    int per = burnTime(ctx, stack);
                    if (per > 0) {
                        int total = per * stack.getCount();
                        candidates.add(new FuelSlot(slot.index, per, stack.getCount(), total));
                    }
                }
            }
        }
        return candidates.stream()
                .filter(f -> f.burnPerItem >= SMELT_TICKS && f.totalBurn >= needTicks)
                .min(Comparator.comparingInt(f -> f.burnPerItem))
                .map(f -> f.slotIndex)
                .orElseGet(() -> candidates.isEmpty() ? -1 : candidates.get(0).slotIndex);
    }

    private record FuelSlot(int slotIndex, int burnPerItem, int count, int totalBurn) {}

    private int burnTime(BotContext ctx, ItemStack stack) {
        return ctx.level.fuelValues().burnDuration(stack);
    }

    private BlockPos findFurnace(BotContext ctx) {
        return BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                Set.of(Blocks.FURNACE), SEARCH_RADIUS,
                ctx.level.getMinY(), ctx.level.getMaxY());
    }

    private static boolean inReach(BotContext ctx, BlockPos pos) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(centre) > INTERACT_REACH_SQR) {
            return false;
        }
        // Distance alone is not an interaction position.  If a wall or the crafting table is
        // between the player and the furnace, let the adjacent route reposition the player
        // instead of repeatedly aiming at an unreachable block forever.
        return BlockPlacer.hasLineOfSight(ctx, pos);
    }

    private void closeMenu(BotContext ctx) {
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            ctx.mc.setScreen(null);
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        closeMenu(ctx);
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (surfaceRecovery != null) {
            surfaceRecovery.stop(ctx);
            surfaceRecovery = null;
        }
        placingFurnacePos = null;
        furnacePlaceTicks = 0;
        furnacePlacementAttempts = 0;
        badPlacementSpots.clear();
        ctx.input.reset();
    }
}
