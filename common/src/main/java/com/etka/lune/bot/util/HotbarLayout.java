package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.catalog.ToolCatalog;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.function.Predicate;

/**
 * Keeps the hotbar in a fixed, known order.
 *
 * <p>Left alone, a freshly crafted or looted item lands in whatever slot happens to be free, and
 * {@link InventoryHelper#equip} swaps whatever it needs into the slot currently held - so the
 * hotbar is different every minute and the same tool moves around during a run. That is fine for
 * the bot, which searches by predicate, and useless for the person watching it: there is no glance
 * that tells you whether it still has a pickaxe, or a boat, or anything to eat.
 *
 * <p>The order below is the one asked for. It is a tidy-up rather than a guarantee: items are moved
 * into place when they are in the wrong slot and nothing is dragged out of a slot it is meant to be
 * in, so a run that has no boat simply leaves that slot empty.
 */
public final class HotbarLayout {

    /** One hotbar slot and what belongs in it. */
    private record Slot(int index, String label, Predicate<ItemStack> match) {}

    private static final List<Slot> LAYOUT = List.of(
            new Slot(0, "crafting table", stack -> stack.is(Items.CRAFTING_TABLE)),
            new Slot(1, "pickaxe", kind(ToolCatalog.Kind.PICKAXE)),
            new Slot(2, "axe", kind(ToolCatalog.Kind.AXE)),
            new Slot(3, "sword", kind(ToolCatalog.Kind.SWORD)),
            new Slot(4, "food", stack -> stack.has(DataComponents.FOOD)),
            new Slot(5, "boat", stack -> stack.getItem() instanceof BoatItem));

    private static Predicate<ItemStack> kind(ToolCatalog.Kind wanted) {
        return stack -> ToolCatalog.kindOf(stack.getItem()) == wanted;
    }

    private HotbarLayout() {}

    /**
     * Moves anything that belongs in a laid-out slot into it.
     *
     * <p>Only ever swaps when the target slot holds the wrong thing, so repeated calls settle and
     * then do nothing. Returns the number of swaps performed, which is zero once the hotbar is
     * tidy - callers use that to avoid logging a tidy-up that did not happen.
     */
    public static int arrange(BotContext ctx) {
        Inventory inventory = ctx.player.getInventory();
        int moved = 0;
        for (Slot slot : LAYOUT) {
            int source = findElsewhere(ctx, slot);
            // "Already matches" is not the same as "already right".
            //
            // Testing only the predicate meant a wooden pickaxe sitting in the pickaxe slot counted
            // as satisfied, so the stone one crafted later stayed wherever it landed - a run ended
            // with `1:wooden_pickaxe` and `7:stone_pickaxe`, tidy and wrong. The slot is settled
            // only when nothing better exists elsewhere.
            ItemStack held = inventory.getItem(slot.index());
            if (slot.match().test(held)
                    && (source < 0 || tierOf(inventory.getItem(source)) <= tierOf(held))) {
                continue;
            }
            if (source < 0) {
                // Nothing to put here yet. Keep the slot clear anyway, so the bar stays the same
                // shape all run and the item drops into its own place the moment it is crafted or
                // picked up. Without this the empty slots fill with whatever came to hand - dirt in
                // the crafting-table slot, cobble in the axe slot - and the layout is invisible.
                clearSlot(ctx, slot);
                continue;
            }
            // Vanilla's swap treats the "button" as a hotbar index, so this exchanges the two
            // stacks in one interaction whether the source is deep in the bag or in another
            // hotbar slot. Exchanging rather than moving is what keeps whatever was already there
            // from being dropped on the floor.
            ctx.gameMode.handleContainerInput(ctx.player.inventoryMenu.containerId,
                    InventoryHelper.menuSlotFor(source), slot.index(),
                    ContainerInput.SWAP, ctx.player);
            moved++;
        }
        return moved;
    }

    /**
     * Empties a reserved slot into the main inventory.
     *
     * <p>Does nothing when the slot is already empty, and nothing when the bag is full - losing an
     * item to keep the bar tidy would be a bad trade, so a full inventory simply keeps its clutter.
     */
    private static void clearSlot(BotContext ctx, Slot slot) {
        Inventory inventory = ctx.player.getInventory();
        if (inventory.getItem(slot.index()).isEmpty()) {
            return;
        }
        for (int i = Inventory.SELECTION_SIZE; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                ctx.gameMode.handleContainerInput(ctx.player.inventoryMenu.containerId,
                        InventoryHelper.menuSlotFor(i), slot.index(),
                        ContainerInput.SWAP, ctx.player);
                return;
            }
        }
    }

    /**
     * The best stack for this slot that is not already in it.
     *
     * <p>Prefers the deep inventory over another laid-out slot: pulling a pickaxe out of the axe
     * slot to fill the pickaxe slot would just move the problem along the bar.
     */
    private static int findElsewhere(BotContext ctx, Slot slot) {
        Inventory inventory = ctx.player.getInventory();
        int best = -1;
        int bestRank = Integer.MIN_VALUE;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (i == slot.index() || !slot.match().test(inventory.getItem(i))) {
                continue;
            }
            // Rank by tier, then prefer the deep inventory over another hotbar slot.
            //
            // Taking the first match put a wooden pickaxe in the pickaxe slot while the stone one
            // sat in slot 7 - the layout was tidy and the tool was wrong, which is worse than
            // untidy. Preferring the bag over the bar on a tie stops a swap from just moving the
            // problem along the hotbar.
            int rank = tierOf(inventory.getItem(i)) * 2 + (i >= Inventory.SELECTION_SIZE ? 1 : 0);
            if (rank > bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        return best;
    }

    /** Tool tier for ranking, or zero for anything that is not a tool. */
    private static int tierOf(ItemStack stack) {
        ToolCatalog.Material material = ToolCatalog.materialOf(stack.getItem());
        return material == null ? 0 : material.ordinal();
    }
}
