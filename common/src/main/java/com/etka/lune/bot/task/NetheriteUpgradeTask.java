package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.NetheriteUpgrades;
import com.etka.lune.bot.learning.LearningScope;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.compat.Screens;
import com.etka.lune.util.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Upgrades one piece of diamond gear to netherite at a smithing table.
 *
 * <p>The last rung of the tool ladder, and the only one that is not crafted. Get Tools stops at
 * diamond on purpose - everything below diamond is made out of what the bot can go and dig, and
 * netherite is not: it needs an upgrade template that generates in bastion chests and cannot be
 * made from raw materials at all. So this is a card of its own, and it does one thing: it puts a
 * template, a diamond something and a netherite ingot into a smithing table and takes out what
 * comes back.</p>
 *
 * <p><b>It asks for its three items rather than fetching them.</b> A missing ingot is a Smelt card
 * and a Craft card in front of this one - four ancient debris into scrap, four scrap and four gold
 * into the ingot - and a missing template is a chest in a bastion that no card can conjure. Saying
 * so plainly is the honest answer; going and getting them would be three other cards' jobs done
 * quietly inside this one.</p>
 *
 * <p>The table itself is the exception, and only the way a furnace is for Smelt: one is placed if
 * the bot is carrying one, and otherwise the card says it needs a smithing table. Placing what you
 * are already holding is not fetching.</p>
 */
public final class NetheriteUpgradeTask implements Task {

    private static final int SEARCH_RADIUS = 8;
    private static final int MAX_OPEN_ATTEMPTS = 4;
    /** Shift-clicks at a finished result before concluding the bag has no room for it. */
    private static final int MAX_TAKE_ATTEMPTS = 4;
    private static final int ACTION_COOLDOWN = 5;
    private static final int MAX_PLACE_TICKS = 40;
    private static final int MAX_PLACEMENT_SPOTS = 4;
    private static final double INTERACT_REACH_SQR = 20.0;

    private final Item base;
    private final Item result;

    private BlockPos tablePos;
    private BlockPos placingTablePos;
    private int placeTicks;
    private int placementAttempts;
    private final Set<Long> badPlacementSpots = new HashSet<>();
    private GotoTask approach;
    private int openAttempts;
    private int takeAttempts;
    private int cooldown;
    private int startResults = -1;
    private final StatusText status = new StatusText();

    public NetheriteUpgradeTask(Item base) {
        this.base = base;
        this.result = NetheriteUpgrades.resultOf(base);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.netherite_upgrade.name",
                InventoryHelper.itemName(result == null ? base : result));
    }

    /** English on purpose: this is the learner's row key, and is never shown. */
    @Override
    public String learningId() {
        return Task.learningName("Upgrade to Netherite");
    }

    @Override
    public LearningScope learningScope() {
        return LearningScope.of(learningId(), "lune.unit.items");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (result == null) {
            // A saved task naming something the smithing table has never heard of - a tool from a
            // mod that has since gone, most often.
            status.set("lune.status.netherite_upgrade.not_upgradeable", InventoryHelper.itemName(base));
            return TaskStatus.FAILED;
        }

        int have = InventoryHelper.count(ctx.player, result);
        if (startResults < 0) {
            startResults = have;
        }
        if (have > startResults) {
            closeMenu(ctx);
            status.set("lune.status.netherite_upgrade.upgraded", InventoryHelper.itemName(result));
            return TaskStatus.SUCCESS;
        }

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        TaskStatus missing = checkIngredients(ctx);
        if (missing != null) {
            return missing;
        }

        if (placingTablePos != null) {
            return placeTable(ctx);
        }

        // A crafting table or furnace screen can survive a handoff for a tick, and it must never
        // stand between the bot and the smithing table it is walking to.
        if (!(ctx.player.containerMenu instanceof SmithingMenu)
                && ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            Screens.open(ctx.mc, null);
        }

        if (tablePos == null || !ctx.level.getBlockState(tablePos).is(Blocks.SMITHING_TABLE)) {
            TaskStatus found = findOrPlaceTable(ctx);
            if (found != null) {
                return found;
            }
        }

        if (!(ctx.player.containerMenu instanceof SmithingMenu menu)) {
            return openTable(ctx);
        }
        return work(ctx, menu);
    }

    /**
     * Fails while anything the smithing needs is still missing.
     *
     * <p>Checked before the walk rather than at the table, so a run that cannot succeed says why
     * where the player is standing instead of after a hike to a table it had no use for.</p>
     */
    private TaskStatus checkIngredients(BotContext ctx) {
        if (!InventoryHelper.has(ctx.player, Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1)) {
            status.set("lune.status.netherite_upgrade.no_template");
            return TaskStatus.FAILED;
        }
        if (!InventoryHelper.has(ctx.player, base, 1)) {
            // Already holding the netherite one is not a failure, it is the job being done.
            if (InventoryHelper.has(ctx.player, result, 1)) {
                status.set("lune.status.netherite_upgrade.already", InventoryHelper.itemName(result));
                return TaskStatus.SUCCESS;
            }
            status.set("lune.status.netherite_upgrade.no_base", InventoryHelper.itemName(base));
            return TaskStatus.FAILED;
        }
        if (!InventoryHelper.has(ctx.player, Items.NETHERITE_INGOT, 1)) {
            status.set("lune.status.netherite_upgrade.no_ingot");
            return TaskStatus.FAILED;
        }
        return null;
    }

    private TaskStatus findOrPlaceTable(BotContext ctx) {
        tablePos = BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                Set.of(Blocks.SMITHING_TABLE), SEARCH_RADIUS,
                ctx.level.getMinY(), ctx.level.getMaxY());
        if (tablePos != null) {
            badPlacementSpots.clear();
            placementAttempts = 0;
            openAttempts = 0;
            if (approach != null) {
                approach.stop(ctx);
                approach = null;
            }
            return null;
        }
        if (!InventoryHelper.has(ctx.player, Items.SMITHING_TABLE, 1)) {
            status.set("lune.status.netherite_upgrade.no_table");
            return TaskStatus.FAILED;
        }
        placingTablePos = BlockPlacer.findPlacementSpot(ctx, badPlacementSpots);
        placeTicks = 0;
        if (placingTablePos == null) {
            status.set("lune.status.netherite_upgrade.nowhere_to_put_table");
            return TaskStatus.FAILED;
        }
        status.set("lune.status.netherite_upgrade.placing_table");
        return TaskStatus.RUNNING;
    }

    /**
     * Placement takes several ticks - the head turns to the support face, the interaction is sent,
     * and only the server's reply puts the block in the world - so the spot is kept across them.
     */
    private TaskStatus placeTable(BotContext ctx) {
        if (ctx.level.getBlockState(placingTablePos).is(Blocks.SMITHING_TABLE)) {
            tablePos = placingTablePos;
            placingTablePos = null;
            placeTicks = 0;
            placementAttempts = 0;
            openAttempts = 0;
            cooldown = ACTION_COOLDOWN;
            status.set("lune.status.netherite_upgrade.placed_table");
            return TaskStatus.RUNNING;
        }

        BlockPlacer.PlacementResult placement = ++placeTicks <= MAX_PLACE_TICKS
                ? BlockPlacer.tryPlace(ctx, Blocks.SMITHING_TABLE, placingTablePos)
                : null;
        if (placement != null && placement.isTransient()) {
            status.set("lune.status.netherite_upgrade.placing_table");
            return TaskStatus.RUNNING;
        }

        badPlacementSpots.add(placingTablePos.asLong());
        placingTablePos = null;
        placeTicks = 0;
        if (++placementAttempts < MAX_PLACEMENT_SPOTS) {
            status.set("lune.status.netherite_upgrade.table_spot_failed");
            return TaskStatus.RUNNING;
        }
        placementAttempts = 0;
        status.set("lune.status.netherite_upgrade.nowhere_to_put_table");
        return TaskStatus.FAILED;
    }

    private TaskStatus openTable(BotContext ctx) {
        if (inReach(ctx, tablePos)) {
            if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                status.set("lune.status.netherite_upgrade.cant_open_table");
                return TaskStatus.FAILED;
            }
            if (BlockPlacer.use(ctx, tablePos)) {
                openAttempts++;
                status.set("lune.status.netherite_upgrade.opening_table");
                cooldown = ACTION_COOLDOWN + 3;
            } else {
                status.set("lune.status.netherite_upgrade.aiming_table");
            }
            return TaskStatus.RUNNING;
        }

        if (approach == null) {
            approach = new GotoTask(new Goals.Adjacent(tablePos, 3.5), false, true);
            approach.start(ctx);
        }
        if (approach.tick(ctx) == TaskStatus.FAILED) {
            approach.stop(ctx);
            approach = null;
            status.set("lune.status.netherite_upgrade.cant_reach_table");
            return TaskStatus.FAILED;
        }
        status.set("lune.status.netherite_upgrade.walking_table");
        return TaskStatus.RUNNING;
    }

    /**
     * Fills the three input slots and takes what appears, one click a tick.
     *
     * <p>Every move is vanilla's own shift-click. The menu decides which slot a stack belongs in -
     * template, base or addition - by asking the same recipe tests the screen asks when a player
     * does it, so nothing here needs to know which slot is which beyond the result.</p>
     */
    private TaskStatus work(BotContext ctx, SmithingMenu menu) {
        ItemStack output = menu.getSlot(SmithingMenu.RESULT_SLOT).getItem();
        if (!output.isEmpty()) {
            // A shift-click that changes nothing means there is nowhere for it to go. Saying so
            // beats clicking a finished pickaxe at a full bag until the run's deadline ends it.
            if (++takeAttempts > MAX_TAKE_ATTEMPTS) {
                status.set("lune.status.netherite_upgrade.bag_full", InventoryHelper.itemName(result));
                return TaskStatus.FAILED;
            }
            ctx.gameMode.handleContainerInput(menu.containerId, SmithingMenu.RESULT_SLOT, 0,
                    ContainerInput.QUICK_MOVE, ctx.player);
            cooldown = ACTION_COOLDOWN;
            status.set("lune.status.netherite_upgrade.taking", InventoryHelper.itemName(result));
            return TaskStatus.RUNNING;
        }
        takeAttempts = 0;

        if (fill(ctx, menu, SmithingMenu.TEMPLATE_SLOT,
                stack -> stack.is(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE))) {
            status.set("lune.status.netherite_upgrade.adding_template");
            return TaskStatus.RUNNING;
        }
        if (fill(ctx, menu, SmithingMenu.BASE_SLOT, stack -> stack.is(base))) {
            status.set("lune.status.netherite_upgrade.adding", InventoryHelper.itemName(base));
            return TaskStatus.RUNNING;
        }
        if (fill(ctx, menu, SmithingMenu.ADDITIONAL_SLOT, stack -> stack.is(Items.NETHERITE_INGOT))) {
            status.set("lune.status.netherite_upgrade.adding", InventoryHelper.itemName(Items.NETHERITE_INGOT));
            return TaskStatus.RUNNING;
        }

        // All three are in and the table is offering nothing. That is the world saying this is not
        // a recipe - a pairing that only looked like one, or a pack that has taken it out.
        status.set("lune.status.netherite_upgrade.no_recipe", InventoryHelper.itemName(base));
        return TaskStatus.FAILED;
    }

    /** Shift-clicks one matching stack out of the inventory, if the slot is still empty. */
    private boolean fill(BotContext ctx, SmithingMenu menu, int slot, Predicate<ItemStack> match) {
        if (!menu.getSlot(slot).getItem().isEmpty()) {
            return false;
        }
        for (Slot candidate : menu.slots) {
            if (candidate.container != ctx.player.getInventory()) {
                continue;
            }
            if (match.test(candidate.getItem())) {
                ctx.gameMode.handleContainerInput(menu.containerId, candidate.index, 0,
                        ContainerInput.QUICK_MOVE, ctx.player);
                cooldown = ACTION_COOLDOWN;
                return true;
            }
        }
        return false;
    }

    private static boolean inReach(BotContext ctx, BlockPos pos) {
        Vec3 eye = ctx.player.getEyePosition();
        if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > INTERACT_REACH_SQR) {
            return false;
        }
        // Distance alone is not an interaction position: a lip of stone between the eyes and the
        // table leaves the card aiming at something it can never click.
        return BlockPlacer.hasLineOfSight(ctx, pos);
    }

    private void closeMenu(BotContext ctx) {
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            Screens.open(ctx.mc, null);
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        closeMenu(ctx);
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        placingTablePos = null;
        placeTicks = 0;
        placementAttempts = 0;
        badPlacementSpots.clear();
        ctx.input.reset();
    }
}
