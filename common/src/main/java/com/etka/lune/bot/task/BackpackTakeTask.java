package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.bot.util.ItemFilters;
import com.etka.lune.util.Lang;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Opens the player's backpack and shift-clicks matching items out of it.
 *
 * <p>Only the compartment is read - never the tool slots, the upgrades or a crafting grid, which
 * would hand the player their own pickaxe as "loot". Stacks move whole, the way a shift-click
 * moves them, so a count is a floor: the card stops once at least that many have come out.
 * A count of zero means everything that matches.</p>
 */
public final class BackpackTakeTask extends BackpackTask {

    private final String filter;
    private final Item item;
    private final int count;
    private final Predicate<ItemStack> matcher;

    private int baseline = -1;
    private int lastSlot = -1;
    private ItemStack lastStack = ItemStack.EMPTY;

    public BackpackTakeTask(String filter, Item item, int count, boolean optional) {
        super(optional);
        this.filter = filter == null || filter.isBlank() ? ItemFilters.ITEM : filter;
        this.item = item;
        this.count = Math.max(0, count);
        this.matcher = ItemFilters.matcher(this.filter, item);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.backpack_take.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Take from Backpack");
    }

    private String what() {
        return ItemFilters.ITEM.equalsIgnoreCase(filter) && item != null
                ? InventoryHelper.itemName(item)
                : Param.Choice.optionLabel(filter);
    }

    private int taken(BotContext ctx) {
        return InventoryHelper.count(ctx.player, matcher) - baseline;
    }

    @Override
    protected TaskStatus work(BotContext ctx, AbstractContainerMenu menu) {
        if (baseline < 0) {
            baseline = InventoryHelper.count(ctx.player, matcher);
        }
        if (count > 0 && taken(ctx) >= count) {
            return done(ctx);
        }
        for (Slot slot : menu.slots) {
            if (!hook.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }
            if (slot.index == lastSlot && unchanged(lastStack, stack)) {
                // The click moved nothing: there is no room left in the inventory.
                return taken(ctx) > 0 ? done(ctx) : unavailable("lune.status.backpack.inventory_full");
            }
            lastSlot = slot.index;
            lastStack = stack.copy();
            quickMove(ctx, menu, slot);
            pause();
            status.set("lune.status.backpack.taking", stack.getCount(), stack.getHoverName().getString());
            return TaskStatus.RUNNING;
        }
        return taken(ctx) > 0 ? done(ctx) : unavailable("lune.status.backpack.nothing_to_take", what());
    }

    private TaskStatus done(BotContext ctx) {
        status.set("lune.status.backpack.took", taken(ctx), what());
        return TaskStatus.SUCCESS;
    }
}
