package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.catalog.CraftPattern;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crafts from a grid the player drew themselves, one ingredient at a time, the way a human does.
 * <p>
 * {@link CraftTask} asks the server to lay out a recipe from the recipe book, which is fast, safe -
 * and limited to what the book knows. The book is unlocked by playing: a recipe the player has never
 * met is not in it, so a bot holding every ingredient still reports that there is no recipe. That is
 * the gap this card fills. Putting items into the grid by hand needs no recipe at all, because the
 * matching happens on the server from the grid contents: if the ingredients make something, the
 * result slot fills, whether or not anyone has ever crafted it before, and whichever mod added it.
 * <p>
 * The trade is that the result is unknown until the server answers, so this card counts crafts
 * rather than items. It fills the grid, waits for a result to appear, takes it, and repeats.
 * <p>
 * A pattern whose filled cells fit inside a 2×2 box is crafted in the player's own grid; anything
 * wider needs a crafting table, which {@link CraftingTableAccess} finds or builds.
 */
public final class GridCraftTask implements Task {

    /** Ticks between container actions, so the server's reply lands before the next one. */
    private static final int ACTION_COOLDOWN = 3;
    /**
     * How long to wait for a result after the grid is full.
     * <p>
     * The client fills its own grid the moment it clicks, but the result is computed on the server
     * and sent back, so an empty result slot means "not yet" for the first few ticks and "these
     * ingredients make nothing" after that. Generous, because being wrong here reads as a recipe
     * that does not exist.
     */
    private static final int RESULT_WAIT_TICKS = 30;
    /** Shift-clicks at a result that will not move before concluding it has nowhere to go. */
    private static final int MAX_TAKE_ATTEMPTS = 4;
    /** Clicks spent emptying a grid before concluding the bag will not take its contents back. */
    private static final int MAX_CLEAR_CLICKS = 12;

    private enum Phase { OPEN_GRID, FILL, WAIT_FOR_RESULT, TAKE, CLEAR }

    /** One container click: the same three things the vanilla client sends when you click a slot. */
    private record Click(int slot, int button, ContainerInput kind) {}

    private final CraftPattern pattern;
    private final int times;
    private final CraftingTableAccess table = new CraftingTableAccess();
    private final Deque<Click> plan = new ArrayDeque<>();

    private Phase phase = Phase.OPEN_GRID;
    private int crafted;
    private int cooldown;
    private int resultWait;
    private int takeAttempts;
    private int clearClicks;
    /** What the last craft produced, so the status line can name something the card never knew. */
    private String producedName = "";
    private final StatusText status = new StatusText();
    /** Set when the run is finishing so the clearing phase knows whether it ends in failure. */
    private boolean clearingToFail;

    public GridCraftTask(CraftPattern pattern, int times) {
        this.pattern = pattern == null ? CraftPattern.empty(CraftPattern.LARGE) : pattern.normalized();
        this.times = Math.max(1, times);
    }

    /** True when this drawing has to be laid out on a crafting table rather than the 2×2 grid. */
    public boolean needsTable() {
        return !pattern.fitsIn(CraftPattern.SMALL);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.grid_craft.craft_by_hand");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Craft by hand");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        return new TaskProgress(crafted, times, Lang.get("lune.unit.crafts"));
    }

    @Override
    public boolean madeProgress() {
        return crafted > 0;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (pattern.isEmpty()) {
            status.set("lune.status.grid_craft.grid_empty_draw_recipe_card_first");
            return TaskStatus.FAILED;
        }
        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        return switch (phase) {
            case OPEN_GRID -> openGrid(ctx);
            case FILL -> fill(ctx);
            case WAIT_FOR_RESULT -> waitForResult(ctx);
            case TAKE -> take(ctx);
            case CLEAR -> clear(ctx);
        };
    }

    /** Gets a grid big enough for the drawing open: the player's own, or a table's. */
    private TaskStatus openGrid(BotContext ctx) {
        if (needsTable()) {
            TaskStatus opened = table.ensureOpen(ctx);
            if (opened == TaskStatus.FAILED) {
                status.set(table.statusLine());
                return TaskStatus.FAILED;
            }
            if (opened == TaskStatus.RUNNING) {
                status.set(table.statusLine());
                return TaskStatus.RUNNING;
            }
            phase = Phase.FILL;
            return TaskStatus.RUNNING;
        }

        // A 2×2 drawing is crafted wherever there is a grid, including a table that is already
        // open - but not through a chest, which has no grid at all.
        if (menu(ctx) != null) {
            phase = Phase.FILL;
            return TaskStatus.RUNNING;
        }
        ctx.player.closeContainer();
        ctx.mc.setScreen(null);
        status.set("lune.status.grid_craft.closing_open_container_use_crafting_grid");
        cooldown = ACTION_COOLDOWN;
        return TaskStatus.RUNNING;
    }

    /** Lays one set of ingredients into the grid, a click at a time. */
    private TaskStatus fill(BotContext ctx) {
        AbstractCraftingMenu menu = menu(ctx);
        if (menu == null) {
            // The grid went away mid-layout. Planned clicks name slots in a menu that is no longer
            // open, so they are thrown away rather than replayed into whatever opens next.
            plan.clear();
            phase = Phase.OPEN_GRID;
            return TaskStatus.RUNNING;
        }

        if (plan.isEmpty()) {
            if (!menu.getCarried().isEmpty()) {
                // Nothing should be on the cursor, but a click lost to a disconnect leaves it
                // there, and every click after that would put the wrong item in the grid.
                int free = firstEmptyPlayerSlot(menu, ctx);
                if (free < 0) {
                    status.set("lune.status.grid_craft.something_stuck_cursor_nowhere_put");
                    return TaskStatus.FAILED;
                }
                click(ctx, menu, new Click(free, 0, ContainerInput.PICKUP));
                return TaskStatus.RUNNING;
            }

            // The grid is only assumed empty at the start of a layout, so anything already in it
            // has to come out first. A While monitor that interrupted the last craft, a previous
            // run, or the player's own leftovers all end up here - and adding a second item to a
            // cell that already has one silently changes the recipe.
            if (!gridIsEmpty(menu)) {
                status.set("lune.status.grid_craft.clearing_what_left_grid");
                return emptyGrid(ctx, menu);
            }
            clearClicks = 0;

            List<Click> built = buildPlan(ctx, menu);
            if (built == null) {
                // status already names the missing ingredient
                return finish(ctx, true);
            }
            plan.addAll(built);
            status.set("lune.status.grid_craft.laying_out_ingredients", pattern.filledCells(), (times > 1 ? " (" + (crafted + 1) + "/" + times + ")" : ""));
        }

        Click next = plan.poll();
        if (next == null) {
            resultWait = 0;
            phase = Phase.WAIT_FOR_RESULT;
            return TaskStatus.RUNNING;
        }
        click(ctx, menu, next);
        if (plan.isEmpty()) {
            resultWait = 0;
            phase = Phase.WAIT_FOR_RESULT;
        }
        return TaskStatus.RUNNING;
    }

    /** The grid is full; the server decides whether that is a recipe. */
    private TaskStatus waitForResult(BotContext ctx) {
        AbstractCraftingMenu menu = menu(ctx);
        if (menu == null) {
            // The grid went away mid-layout. Planned clicks name slots in a menu that is no longer
            // open, so they are thrown away rather than replayed into whatever opens next.
            plan.clear();
            phase = Phase.OPEN_GRID;
            return TaskStatus.RUNNING;
        }
        ItemStack result = menu.getResultSlot().getItem();
        if (!result.isEmpty()) {
            producedName = result.getHoverName().getString();
            status.set("lune.status.kill.crafting", producedName);
            phase = Phase.TAKE;
            return TaskStatus.RUNNING;
        }
        if (++resultWait < RESULT_WAIT_TICKS) {
            status.set("lune.status.grid_craft.waiting_result");
            return TaskStatus.RUNNING;
        }
        status.set("lune.status.grid_craft.those_ingredients_do_not_make_anything");
        return finish(ctx, true);
    }

    /** Shift-clicks the result out, which is also what consumes the grid. */
    private TaskStatus take(BotContext ctx) {
        AbstractCraftingMenu menu = menu(ctx);
        if (menu == null) {
            // The grid went away mid-layout. Planned clicks name slots in a menu that is no longer
            // open, so they are thrown away rather than replayed into whatever opens next.
            plan.clear();
            phase = Phase.OPEN_GRID;
            return TaskStatus.RUNNING;
        }
        if (menu.getResultSlot().getItem().isEmpty()) {
            // The quick move already happened and the grid is spent; count it and go again.
            if (gridIsEmpty(menu)) {
                crafted++;
                takeAttempts = 0;
                ctx.debug.count("items_crafted", 1);
                status.set("lune.status.grid_craft.crafted", producedName, crafted, times);
                if (crafted >= times) {
                    return finish(ctx, false);
                }
                phase = Phase.FILL;
                return TaskStatus.RUNNING;
            }
            // A result that vanished while the grid stayed full means the craft did not land -
            // an inventory with no room for it is the ordinary cause.
            if (InventoryHelper.isFull(ctx.player)) {
                status.set("lune.status.grid_craft.no_room_bag_what_makes");
            } else {
                status.set("lune.status.grid_craft.craft_did_not_go_through");
            }
            return finish(ctx, true);
        }

        // A result that will not move is a full inventory: shift-clicking it again cannot help,
        // and without this the card would click at it forever.
        if (++takeAttempts > MAX_TAKE_ATTEMPTS) {
            status.set("lune.status.grid_craft.no_room_bag", producedName);
            return finish(ctx, true);
        }
        click(ctx, menu, new Click(menu.getResultSlot().index, 0, ContainerInput.QUICK_MOVE));
        return TaskStatus.RUNNING;
    }

    /** Puts back whatever is still sitting in the grid, so an abandoned craft costs nothing. */
    private TaskStatus clear(BotContext ctx) {
        AbstractCraftingMenu menu = menu(ctx);
        if (menu == null) {
            return clearingToFail ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        }
        if (!gridIsEmpty(menu)) {
            return emptyGrid(ctx, menu);
        }
        closeMenu(ctx);
        return clearingToFail ? TaskStatus.FAILED : TaskStatus.SUCCESS;
    }

    /**
     * Shift-clicks one grid slot back into the bag, and gives up rather than clicking forever.
     * <p>
     * An inventory with no free slot cannot take the ingredients back, and every retry then does
     * exactly nothing - which is the shape of a bot that looks busy and is not.
     */
    private TaskStatus emptyGrid(BotContext ctx, AbstractCraftingMenu menu) {
        if (++clearClicks > MAX_CLEAR_CLICKS) {
            status.set("lune.status.grid_craft.cannot_empty_crafting_grid_bag_full");
            closeMenu(ctx);
            return TaskStatus.FAILED;
        }
        for (Slot slot : menu.getInputGridSlots()) {
            if (!slot.getItem().isEmpty()) {
                click(ctx, menu, new Click(slot.index, 0, ContainerInput.QUICK_MOVE));
                return TaskStatus.RUNNING;
            }
        }
        clearClicks = 0;
        return TaskStatus.RUNNING;
    }

    /**
     * Ends the run, emptying the grid first.
     * <p>
     * A table returns its grid when the screen closes, but the player's own 2×2 does not: items
     * left there stay in limbo until the inventory screen is opened by hand. Emptying it is the
     * difference between a failed craft costing nothing and it quietly eating the ingredients.
     */
    private TaskStatus finish(BotContext ctx, boolean failed) {
        clearingToFail = failed;
        plan.clear();
        phase = Phase.CLEAR;
        return clear(ctx);
    }

    /**
     * The clicks that fill the grid once, or null when something is missing.
     * <p>
     * Built against a running tally of what each inventory slot holds rather than against the live
     * inventory, so a pattern taking three planks from a stack of two and one from another does not
     * plan four clicks on the slot that only has two.
     */
    private List<Click> buildPlan(BotContext ctx, AbstractCraftingMenu menu) {
        Map<Integer, Integer> remaining = new HashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == ctx.player.getInventory() && !slot.getItem().isEmpty()) {
                remaining.put(slot.index, slot.getItem().getCount());
            }
        }

        List<Slot> gridSlots = menu.getInputGridSlots();
        int gridWidth = menu.getGridWidth();
        // Grouped by source so one stack is picked up once and dealt out cell by cell, rather than
        // being picked up and put back for every single cell.
        Map<Integer, List<Integer>> bySource = new LinkedHashMap<>();
        for (int row = 0; row < pattern.size(); row++) {
            for (int column = 0; column < pattern.size(); column++) {
                Item wanted = pattern.cell(row, column);
                if (wanted == null) {
                    continue;
                }
                if (row >= menu.getGridHeight() || column >= gridWidth) {
                    status.set("lune.status.grid_craft.drawing_needs_bigger_grid_than_one_open");
                    return null;
                }
                int source = pickSource(ctx, menu, wanted, remaining);
                if (source < 0) {
                    status.set("lune.status.grid_craft.not_carrying_enough", InventoryHelper.itemName(wanted), (crafted > 0 ? " after " + crafted + " of " + times : ""));
                    return null;
                }
                remaining.merge(source, -1, Integer::sum);
                bySource.computeIfAbsent(source, ignored -> new ArrayList<>())
                        .add(gridSlots.get(row * gridWidth + column).index);
            }
        }

        List<Click> clicks = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : bySource.entrySet()) {
            clicks.add(new Click(entry.getKey(), 0, ContainerInput.PICKUP));
            for (int target : entry.getValue()) {
                // Right click: one item out of the held stack, which is exactly one per cell.
                clicks.add(new Click(target, 1, ContainerInput.PICKUP));
            }
            // Puts the rest of the stack back where it came from, so the next plan can find it.
            clicks.add(new Click(entry.getKey(), 0, ContainerInput.PICKUP));
        }
        return clicks;
    }

    /** The inventory slot to take the next copy of an item from, or -1 when there is none left. */
    private int pickSource(BotContext ctx, AbstractCraftingMenu menu, Item wanted,
                           Map<Integer, Integer> remaining) {
        int best = -1;
        for (Slot slot : menu.slots) {
            if (slot.container != ctx.player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.is(wanted) || remaining.getOrDefault(slot.index, 0) <= 0) {
                continue;
            }
            // Prefer the smallest surviving stack, which is what tidies loose singles away first
            // and keeps a full stack available for the next craft.
            if (best < 0 || remaining.get(slot.index) < remaining.get(best)) {
                best = slot.index;
            }
        }
        return best;
    }

    private void click(BotContext ctx, AbstractContainerMenu menu, Click click) {
        ctx.gameMode.handleContainerInput(menu.containerId, click.slot(), click.button(),
                click.kind(), ctx.player);
        cooldown = ACTION_COOLDOWN;
    }

    private static boolean gridIsEmpty(AbstractCraftingMenu menu) {
        for (Slot slot : menu.getInputGridSlots()) {
            if (!slot.getItem().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int firstEmptyPlayerSlot(AbstractCraftingMenu menu, BotContext ctx) {
        for (Slot slot : menu.slots) {
            if (slot.container == ctx.player.getInventory() && slot.getItem().isEmpty()
                    && slot.getContainerSlot() < Inventory.INVENTORY_SIZE) {
                return slot.index;
            }
        }
        return -1;
    }

    /** The open crafting grid, or null when what is open has no grid big enough for the drawing. */
    private AbstractCraftingMenu menu(BotContext ctx) {
        if (!(ctx.player.containerMenu instanceof AbstractCraftingMenu crafting)) {
            return null;
        }
        int wide = Math.max(pattern.boundingWidth(), pattern.boundingHeight());
        return crafting.getGridWidth() >= wide && crafting.getGridHeight() >= wide ? crafting : null;
    }

    private void closeMenu(BotContext ctx) {
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            ctx.mc.setScreen(null);
        }
    }

    /**
     * A While monitor takes over mid-layout, so the grid has to be handed back before it does.
     * <p>
     * The shared pause closes an open container, which returns a table's grid on its own - but the
     * player's own 2×2 is never closed, so half a recipe would sit in it, unseen, until the run
     * came back and laid a second copy on top of it.
     */
    @Override
    public void onPause(BotContext ctx) {
        handBackEverything(ctx);
        plan.clear();
        phase = Phase.OPEN_GRID;
        Task.super.onPause(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        table.stop(ctx);
        plan.clear();
        handBackEverything(ctx);
        closeMenu(ctx);
        ctx.input.reset();
    }

    /**
     * Empties the cursor and the grid in one go.
     * <p>
     * Best effort and unspaced, because a cancelled card does not get more ticks: what is on the
     * cursor when a container closes is thrown on the floor, and what is in the player's own grid
     * is invisible until they next open their inventory. Both are ingredients the player owns.
     */
    private void handBackEverything(BotContext ctx) {
        if (!(ctx.player.containerMenu instanceof AbstractCraftingMenu crafting)) {
            return;
        }
        if (!crafting.getCarried().isEmpty()) {
            int free = firstEmptyPlayerSlot(crafting, ctx);
            if (free >= 0) {
                ctx.gameMode.handleContainerInput(crafting.containerId, free, 0,
                        ContainerInput.PICKUP, ctx.player);
            }
        }
        for (Slot slot : crafting.getInputGridSlots()) {
            if (!slot.getItem().isEmpty()) {
                ctx.gameMode.handleContainerInput(crafting.containerId, slot.index, 0,
                        ContainerInput.QUICK_MOVE, ctx.player);
            }
        }
    }
}
