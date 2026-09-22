package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.util.ItemFilters;
import com.etka.lune.mods.Backpacks;
import com.etka.lune.util.Lang;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Opens the player's backpack and shift-clicks matching items into it.
 *
 * <p>The same filters as the chest deposit, so a run that ends "deposit ores" can end it into a
 * backpack when there is no chest for miles. A stack the backpack will not take - it is full, or
 * the mod's filters refuse it - is noticed by the click changing nothing, skipped, and reported
 * at the end: the card fails then, so the graph can go and find a chest, unless it was marked
 * optional.</p>
 */
public final class BackpackDepositTask extends BackpackTask {

    private final String filter;
    private final Predicate<ItemStack> matcher;
    /** Player slots whose stack the backpack refused. */
    private final Set<Integer> refused = new HashSet<>();
    private int lastSlot = -1;
    private ItemStack lastStack = ItemStack.EMPTY;
    private int packed;

    public BackpackDepositTask(String filter, boolean optional) {
        super(optional);
        this.filter = filter == null || filter.isBlank() ? ItemFilters.ALL : filter;
        this.matcher = ItemFilters.matcher(this.filter, null);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.backpack_deposit.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Deposit to Backpack");
    }

    @Override
    protected TaskStatus work(BotContext ctx, AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (!isPlayerSlot(ctx, slot) || refused.contains(slot.index)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            // A backpack never goes into a backpack, whatever the filter says.
            if (stack.isEmpty() || Backpacks.isBackpack(stack) || !matcher.test(stack)) {
                continue;
            }
            if (slot.index == lastSlot && unchanged(lastStack, stack)) {
                refused.add(slot.index);
                continue;
            }
            lastSlot = slot.index;
            lastStack = stack.copy();
            packed += stack.getCount();
            quickMove(ctx, menu, slot);
            pause();
            status.set("lune.status.backpack.depositing", stack.getCount(), stack.getHoverName().getString());
            return TaskStatus.RUNNING;
        }
        if (!refused.isEmpty()) {
            return unavailable("lune.status.backpack.full", hook.label());
        }
        status.set("lune.status.backpack.deposited", Param.Choice.optionLabel(filter));
        return TaskStatus.SUCCESS;
    }
}
