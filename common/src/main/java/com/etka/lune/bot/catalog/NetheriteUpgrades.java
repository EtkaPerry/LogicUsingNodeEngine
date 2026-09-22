package com.etka.lune.bot.catalog;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.Comparator;
import java.util.List;

/**
 * Which gear a smithing table turns into netherite, asked of the registry rather than listed here.
 *
 * <p>The pairing is a naming convention the game keeps and mods copy: {@code diamond_pickaxe}
 * becomes {@code netherite_pickaxe}, in the same namespace. Reading it off the registry is what
 * lets a version that adds a new piece of gear - this one added a spear and a set of nautilus
 * armour - appear in the card without Lune being told, and lets a pack's own diamond tools upgrade
 * if that pack follows the convention.</p>
 *
 * <p>Two things keep the list honest:</p>
 * <ul>
 *   <li><b>Both halves must exist.</b> Diamond ore has no netherite counterpart, so it is not
 *       offered.</li>
 *   <li><b>It has to be gear.</b> A diamond block pairs with a netherite block by name and is not
 *       a smithing recipe at all, so anything that places a block is dropped. Asking the item
 *       whether it is a tool or something worn would read better and cannot be done: an item's
 *       components are not bound until a world binds them, so the question throws in a headless
 *       test and takes the whole card registry's class initialiser with it.</li>
 * </ul>
 *
 * <p>What this is <em>not</em> is a promise that the recipe exists - only the world knows that, and
 * only once it has sent its recipes. The card built from this fails cleanly at the table when a
 * pairing turns out to be a coincidence, the same as any other card that asks for something the
 * world will not give it.</p>
 */
public final class NetheriteUpgrades {

    private static final String DIAMOND = "diamond_";
    private static final String NETHERITE = "netherite_";

    private static List<Item> bases;

    private NetheriteUpgrades() {}

    /** Every diamond item with a netherite counterpart, for the Upgrade to Netherite picker. */
    public static List<Item> bases() {
        if (bases == null) {
            bases = BuiltInRegistries.ITEM.stream()
                    .filter(item -> resultOf(item) != null)
                    .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                    .toList();
        }
        return bases;
    }

    /** What {@code base} becomes in a smithing table, or null when it is not upgradeable gear. */
    public static Item resultOf(Item base) {
        if (base == null || base instanceof BlockItem) {
            return null;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(base);
        if (!key.getPath().startsWith(DIAMOND)) {
            return null;
        }
        Identifier upgraded = Identifier.fromNamespaceAndPath(key.getNamespace(),
                NETHERITE + key.getPath().substring(DIAMOND.length()));
        return BuiltInRegistries.ITEM.getOptional(upgraded).orElse(null);
    }
}
