package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.catalog.ToolCatalog;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks the right tool for a block and makes sure it is actually in the player's hand.
 * <p>
 * Two separate things matter and they are easy to conflate. <b>Speed</b> is how long the block takes
 * - a shovel on stone still works, just slowly. <b>Correctness</b> is whether it drops anything at
 * all: mining diamond ore with a stone pickaxe destroys it and yields nothing. An AFK bot that gets
 * this wrong doesn\u2019t fail loudly, it just quietly deletes ore for an hour, so blocks that need a
 * tool the player doesn\u2019t have are refused rather than mined.
 * <p>
 * The whole inventory is searched, not just the hotbar, so a freshly crafted tool that lands in the
 * main pack is still usable. When needed it is swapped into the selected hotbar slot. Which tool is
 * {@link ToolCatalog#best}'s answer, the one a Lumi's hands use too.
 */
public final class ToolSelector {

    public static final int NO_SLOT = -1;

    private ToolSelector() {}

    /**
     * @return the inventory slot that breaks {@code state} fastest while still dropping it, or
     *         {@link #NO_SLOT} when nothing the player has can harvest it
     */
    public static int bestSlot(Player player, BlockState state) {
        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            stacks.add(inventory.getItem(slot));
        }
        return ToolCatalog.best(stacks, state);
    }

    /** Whether this block can be mined for drops with anything in the player's inventory. */
    public static boolean canHarvest(Player player, BlockState state) {
        return bestSlot(player, state) != NO_SLOT;
    }

    /**
     * Selects the best tool for the block and moves it into the active hotbar slot when it lives in
     * the main inventory.
     *
     * @return false when nothing in the inventory can harvest it, in which case the caller must not
     *         start breaking
     */
    public static boolean equipFor(BotContext ctx, BlockState state) {
        int slot = bestSlot(ctx.player, state);
        if (slot == NO_SLOT) {
            return false;
        }
        if (slot < Inventory.SELECTION_SIZE) {
            ctx.player.getInventory().setSelectedSlot(slot);
            return true;
        }
        // Swap from the main inventory into the currently selected hotbar slot.
        ItemStack tool = ctx.player.getInventory().getItem(slot);
        return InventoryHelper.equip(ctx, stack -> !stack.isEmpty() && stack == tool) >= 0;
    }
}
