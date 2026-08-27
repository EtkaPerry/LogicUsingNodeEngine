package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

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
 * main pack is still usable. When needed it is swapped into the selected hotbar slot.
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
        boolean needsTool = state.requiresCorrectToolForDrops();

        // What an empty hand would manage on this block, as the bar every tool has to beat.
        float handSpeed = ItemStack.EMPTY.getDestroySpeed(state);

        int best = NO_SLOT;
        float bestSpeed = -1.0F;
        boolean bestIsMiningTool = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            // A wrong tool here isn\u2019t slow, it\u2019s destructive - skip it entirely.
            if (needsTool && !stack.isCorrectToolForDrops(state)) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            boolean isMiningTool = isMiningTool(stack);
            // A tool that is no faster than bare hands is the wrong tool.
            //
            // Dirt, grass and sand break at the same speed with a pickaxe as with nothing at all -
            // the pickaxe just loses durability doing it, and a staircase goes through a lot of
            // dirt. Skipping a tool that offers no speed at all leaves the bare hand as the best
            // option, which is what a player uses. It does not affect stone or wood, where the
            // right tool is genuinely faster and so wins on speed anyway.
            if (isMiningTool && speed <= handSpeed && !needsTool) {
                continue;
            }
            // A flower, food, or other arbitrary item can tie a bare-hand speed on dirt/grass.
            // Prefer an actual mining tool on ties so route clearing does not consume a task's
            // decorative or edible stack and the next interaction starts with a sensible hand.
            if (speed > bestSpeed || (speed == bestSpeed && isMiningTool && !bestIsMiningTool)) {
                bestSpeed = speed;
                best = slot;
                bestIsMiningTool = isMiningTool;
            }
        }
        return best;
    }

    private static boolean isMiningTool(ItemStack stack) {
        return stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SHOVELS)
                || stack.is(ItemTags.HOES);
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
