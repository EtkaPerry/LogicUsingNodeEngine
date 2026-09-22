package com.etka.lune.mods;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.util.Lang;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * One backpack mod: how to tell its backpack apart, how to open it, and which of the open menu's
 * slots are the compartment rather than the tools, the upgrades or the crafting grid.
 *
 * <p>Opening is the only thing a hook asks the game to do, and it asks the way the player would:
 * the mod's own open-backpack request, or holding the backpack and using it. Once the menu is
 * open, moving items is vanilla's shift-click through the mod's own menu code, so a backpack can
 * only ever accept what it would accept from the player's hand.</p>
 */
public abstract class BackpackHook extends ModHook {

    /** What became of an open request. */
    public enum Opening {
        /** The request went out; the menu should appear within a few ticks. */
        SENT,
        /** The backpack was brought into the hand first; ask again in a couple of ticks. */
        EQUIPPING,
        /** There is nothing to open. */
        NONE
    }

    /** Key of the mod's name in the language file. */
    protected abstract String labelKey();

    public final String label() {
        return Lang.get(labelKey());
    }

    /** Whether this stack is one of the mod's backpacks. */
    public abstract boolean isBackpack(ItemStack stack);

    /** Whether the open menu is this mod's backpack. */
    public abstract boolean isBackpackMenu(AbstractContainerMenu menu);

    /** A slot of the compartment: something the player may freely put into or take out of. */
    public abstract boolean isStorageSlot(Slot slot);

    /** Asks the game to open the player's backpack. */
    public abstract Opening open(BotContext ctx);

    /**
     * Whether the player seems to have one of these. Worn on the back through an accessory slot
     * or carried in the inventory both count; a mod whose worn backpack is invisible to the client
     * may answer yes on trust and let the open attempt decide.
     */
    public boolean carried(Player player) {
        return ready() && (InventoryHelper.anyMatch(player, this::isBackpack)
                || WornItems.anyMatch(player, this::isBackpack));
    }
}
