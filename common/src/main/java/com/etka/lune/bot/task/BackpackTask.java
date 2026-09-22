package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.compat.Screens;
import com.etka.lune.mods.BackpackHook;
import com.etka.lune.mods.Backpacks;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The half of a backpack card that gets the backpack open.
 *
 * <p>Both backpack cards begin the same way: find which mod's backpack the player has, ask the
 * game to open it the way the player would, wait for the menu, and close it again when done.
 * What happens between - things going in, things coming out - is the subclass's {@link #work}.
 * Every item movement is vanilla's shift-click through the mod's own menu, so a backpack only
 * ever takes what it would take from the player's hand, and its upgrade and tool slots are never
 * touched.</p>
 */
abstract class BackpackTask implements Task {

    protected static final int ACTION_COOLDOWN = 5;
    /** How long an open request is given before it is tried again. */
    private static final int OPEN_COOLDOWN = 10;
    /** Ticks for a hand change to reach the server before the held backpack is used. */
    private static final int SETTLE_TICKS = 3;
    private static final int MAX_OPEN_ATTEMPTS = 6;

    protected final boolean optional;
    protected final StatusText status = new StatusText();
    protected BackpackHook hook;

    private int cooldown;
    private int openAttempts;

    protected BackpackTask(boolean optional) {
        this.optional = optional;
    }

    /** Putting things away is not a skill; there is nothing here for the learner to rank. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }
        if (hook == null) {
            hook = Backpacks.carried(ctx.player);
            if (hook == null) {
                return unavailable("lune.status.backpack.none");
            }
        }

        AbstractContainerMenu menu = ctx.player.containerMenu;
        if (!hook.isBackpackMenu(menu)) {
            if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                return unavailable("lune.status.backpack.cannot_open", hook.label());
            }
            if (menu != null && menu != ctx.player.inventoryMenu) {
                // Something else is open - a chest, a crafting table - and the game will not
                // open a second menu over it.
                closeMenu(ctx);
                cooldown = ACTION_COOLDOWN;
                return TaskStatus.RUNNING;
            }
            openAttempts++;
            switch (hook.open(ctx)) {
                case SENT -> cooldown = OPEN_COOLDOWN;
                case EQUIPPING -> cooldown = SETTLE_TICKS;
                case NONE -> {
                    return unavailable("lune.status.backpack.none");
                }
            }
            status.set("lune.status.backpack.opening", hook.label());
            return TaskStatus.RUNNING;
        }

        TaskStatus result = work(ctx, menu);
        if (result != TaskStatus.RUNNING) {
            closeMenu(ctx);
        }
        return result;
    }

    /** One tick of the card's own job, with the backpack menu open. */
    protected abstract TaskStatus work(BotContext ctx, AbstractContainerMenu menu);

    /** Waits before the next tick of work, giving the server time to move what was just clicked. */
    protected void pause() {
        cooldown = ACTION_COOLDOWN;
    }

    protected static void quickMove(BotContext ctx, AbstractContainerMenu menu, Slot slot) {
        ctx.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, ctx.player);
    }

    protected static boolean isPlayerSlot(BotContext ctx, Slot slot) {
        return slot.container == ctx.player.getInventory();
    }

    /** Whether the stack in a slot is the one that was there last time - the sign a click did nothing. */
    protected static boolean unchanged(ItemStack before, ItemStack now) {
        return before != null && !before.isEmpty() && !now.isEmpty()
                && before.getCount() == now.getCount()
                && ItemStack.isSameItemSameComponents(before, now);
    }

    protected TaskStatus unavailable(String key, Object... args) {
        if (optional) {
            status.set("lune.status.backpack.skipping_optional", new StatusText().set(key, args));
            return TaskStatus.SUCCESS;
        }
        status.set(key, args);
        return TaskStatus.FAILED;
    }

    protected void closeMenu(BotContext ctx) {
        if (ctx.player.containerMenu != ctx.player.inventoryMenu) {
            ctx.player.closeContainer();
            Screens.open(ctx.mc, null);
        }
    }

    @Override
    public void onStop(BotContext ctx) {
        closeMenu(ctx);
        ctx.input.reset();
    }
}
