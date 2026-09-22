package com.etka.lune.mods;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** The backpack mods Lune can open, and which of them the player is actually carrying. */
public final class Backpacks {

    private static final List<BackpackHook> ALL = List.of(
            new SophisticatedBackpacksHook(), new TravelersBackpackHook());

    private Backpacks() {}

    /**
     * The mod whose backpack the player has, or null. A backpack that can be seen wins over one
     * that is merely possible, so a player carrying a Sophisticated backpack with Traveler's
     * Backpack also installed opens the one they carry.
     */
    public static BackpackHook carried(Player player) {
        BackpackHook trusted = null;
        for (BackpackHook hook : ALL) {
            if (!hook.ready()) {
                continue;
            }
            boolean seen = com.etka.lune.bot.util.InventoryHelper.anyMatch(player, hook::isBackpack)
                    || WornItems.anyMatch(player, hook::isBackpack);
            if (seen) {
                return hook;
            }
            if (trusted == null && hook.carried(player)) {
                trusted = hook;
            }
        }
        return trusted;
    }

    /**
     * Whether any backpack mod is installed at all.
     *
     * <p>A question about the pack, not about the player: what decides whether the two backpack
     * cards are offered in the palette. Whether one is being carried right now is
     * {@link #carried}, and that is the card's own problem once it runs.</p>
     */
    public static boolean anyInstalled() {
        for (BackpackHook hook : ALL) {
            if (hook.installed()) {
                return true;
            }
        }
        return false;
    }

    /** Whether this stack is any mod's backpack: the one thing a deposit must never pack. */
    public static boolean isBackpack(ItemStack stack) {
        for (BackpackHook hook : ALL) {
            if (hook.isBackpack(stack)) {
                return true;
            }
        }
        return false;
    }
}
