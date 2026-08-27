package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.catalog.ToolCatalog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Counting and moving items without a human at the mouse.
 * <p>
 * The hotbar/main-inventory split matters more than it looks: a freshly crafted pickaxe lands
 * wherever there's room, and {@link ToolSelector} only ever equips from the hotbar. Crafting a tool
 * and then being unable to use it is exactly the kind of silent dead end an unattended bot gets
 * stuck in, so anything the bot needs to hold gets moved into the hotbar first.
 */
public final class InventoryHelper {

    /** Slot in {@link InventoryMenu} where the main inventory starts. */
    private static final int MENU_MAIN_START = 9;
    /** Slot in {@link InventoryMenu} where the hotbar starts. */
    private static final int MENU_HOTBAR_START = 36;

    private InventoryHelper() {}

    /**
     * The name of an item the bot does not hold yet.
     *
     * <p>{@code Item.getName(ItemStack)} reads the {@code ITEM_NAME} data component off the stack
     * it is given, so passing {@link ItemStack#EMPTY} - the obvious way to ask an {@link Item} for
     * its own name - returns the empty string rather than "Stone Pickaxe". Every caller that did
     * that was silently naming a task "Get a " or "Craft ", both in the learned profile and on the
     * Main tab. A one-item stack has the component, so it answers the question the callers are
     * actually asking.</p>
     */
    public static String itemName(Item item) {
        return item == null ? "" : new ItemStack(item).getHoverName().getString();
    }

    public static int count(Player player, Item item) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Counts anything matching, so callers can ask for "planks" rather than "oak planks". */
    public static int count(Player player, Predicate<ItemStack> match) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && match.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean has(Player player, Item item, int atLeast) {
        return count(player, item) >= atLeast;
    }

    /**
     * Total number of items carried, regardless of type. This is useful when an action can produce
     * several legitimate drops (ores, modded blocks, Fortune) and merely seeing an item entity
     * disappear is not proof that this player collected it.
     */
    public static int totalItemCount(Player player) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            total += inventory.getItem(slot).getCount();
        }
        return total;
    }

    /** First inventory slot matching, or -1. Hotbar slots (0-8) are searched first. */
    public static int findSlot(Player player, Predicate<ItemStack> match) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (match.test(inventory.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    /** True if anything in the whole inventory matches. */
    public static boolean anyMatch(Player player, Predicate<ItemStack> match) {
        return findSlot(player, match) >= 0;
    }

    /**
     * Makes sure a matching item is in the hotbar and selects it.
     * <p>
     * Items deeper in the inventory are swapped up using the player's own inventory menu, which is
     * always open behind whatever screen is showing, so this works without opening anything.
     *
     * @return the selected hotbar slot, or -1 if nothing matched
     */
    public static int equip(BotContext ctx, Predicate<ItemStack> match) {
        Inventory inventory = ctx.player.getInventory();

        for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
            if (match.test(inventory.getItem(slot))) {
                inventory.setSelectedSlot(slot);
                return slot;
            }
        }

        int deepSlot = findSlot(ctx.player, match);
        if (deepSlot < Inventory.SELECTION_SIZE) {
            return -1; // not found at all (hotbar was already checked above)
        }

        // Swap it into whichever hotbar slot we're holding. SWAP's "button" is the hotbar index.
        int hotbarTarget = inventory.getSelectedSlot();
        ctx.gameMode.handleContainerInput(ctx.player.inventoryMenu.containerId,
                menuSlotFor(deepSlot), hotbarTarget, ContainerInput.SWAP, ctx.player);
        inventory.setSelectedSlot(hotbarTarget);
        return hotbarTarget;
    }

    /**
     * Selects a real melee weapon before falling back to vanilla's broad weapon component.
     * Modern item components can mark tools as weapons, so a plain WEAPON search may keep
     * selecting a pickaxe while a sword sits in the main inventory.
     */
    public static int equipCombatWeapon(BotContext ctx) {
        int slot = equip(ctx, stack -> ToolCatalog.kindOf(stack.getItem()) == ToolCatalog.Kind.SWORD);
        if (slot >= 0) {
            return slot;
        }
        slot = equip(ctx, stack -> ToolCatalog.kindOf(stack.getItem()) == ToolCatalog.Kind.AXE);
        if (slot >= 0) {
            return slot;
        }
        return equip(ctx, stack -> stack.has(DataComponents.WEAPON));
    }

    /** True for a carried item that is appropriate to hold in a close melee encounter. */
    public static boolean isMeleeWeapon(ItemStack stack) {
        ToolCatalog.Kind kind = ToolCatalog.kindOf(stack.getItem());
        return kind == ToolCatalog.Kind.SWORD || kind == ToolCatalog.Kind.AXE;
    }

    /** True for an item that is a usable combat weapon, including a bow for ranged tasks. */
    public static boolean isCombatWeapon(ItemStack stack) {
        return isMeleeWeapon(stack) || stack.is(net.minecraft.world.item.Items.BOW);
    }

    /**
     * Moves the first matching item into the offhand slot and leaves it equipped there.
     *
     * <p>The inventory protocol uses swap button 40 for the offhand. Keeping this operation here
     * means combat tasks do not have to know the menu slot layout, and it works for items found in
     * either the hotbar or the main inventory.</p>
     *
     * @return true when the offhand now matches, false when no matching item is carried
     */
    public static boolean equipOffhand(BotContext ctx, Predicate<ItemStack> match) {
        Inventory inventory = ctx.player.getInventory();
        ItemStack offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (match.test(offhand)) {
            return true;
        }

        int slot = findSlot(ctx.player, match);
        if (slot < 0 || slot == Inventory.SLOT_OFFHAND) {
            return false;
        }

        ctx.gameMode.handleContainerInput(ctx.player.inventoryMenu.containerId,
                menuSlotFor(slot), Inventory.SLOT_OFFHAND, ContainerInput.SWAP, ctx.player);
        return match.test(inventory.getItem(Inventory.SLOT_OFFHAND));
    }

    /** Maps an {@link Inventory} index to its slot number in the player's inventory menu. */
    static int menuSlotFor(int inventorySlot) {
        return inventorySlot < Inventory.SELECTION_SIZE
                ? MENU_HOTBAR_START + inventorySlot
                : inventorySlot;
    }

    /** Free slots left, used by the "stop when full" safety check and by gathering tasks. */
    public static boolean isFull(Player player) {
        return freeSlots(player) == 0;
    }

    /** Number of empty main-inventory/hotbar slots; armour and offhand are not storage capacity. */
    public static int freeSlots(Player player) {
        int free = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }
}
