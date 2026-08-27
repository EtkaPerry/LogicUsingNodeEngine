package com.etka.lune.bot.catalog;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Splits the block registry into the same groups the player already knows from the creative
 * inventory, so a picker never has to present one undifferentiated list of every block.
 * <p>
 * The grouping is the game's own: one category per creative mode tab, which is also how mods
 * separate their own content - a mod that registers "Create: Kinetics" gets that tab here for free,
 * with no knowledge of the mod on our side. Blocks no tab claims (crops, fluids, technical blocks,
 * and anything from a mod that ships no tab) fall back to a per-mod bucket so they are still
 * reachable rather than silently missing.
 */
public final class BlockCategories {

    /** One entry in a picker's category list. */
    public record Category(String id, String name, ItemStack icon, List<Block> blocks) {}

    private static final String VANILLA = "minecraft";

    private static List<Category> cached;

    private BlockCategories() {}

    /**
     * Brings the creative tab contents up to date for the current session. Tab contents are built
     * lazily by the client - a player who has never opened the creative inventory has empty tabs -
     * so a picker must ask for this before reading {@link #categories()}.
     */
    public static void refresh(FeatureFlagSet enabledFeatures, boolean operatorItems, HolderLookup.Provider holders) {
        if (CreativeModeTabs.tryRebuildTabContents(enabledFeatures, operatorItems, holders)) {
            cached = null;
        }
    }

    /**
     * Every category, vanilla first. Safe to call before {@link #refresh}: with no tab contents
     * available yet the whole registry simply arrives grouped by mod instead of by creative tab.
     */
    public static List<Category> categories() {
        if (cached == null) {
            cached = build();
        }
        return cached;
    }

    private static List<Category> build() {
        List<Category> fromTabs = new ArrayList<>();
        Set<Block> claimed = new LinkedHashSet<>();

        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            // Search, inventory and hotbar are views onto the other tabs, not groupings of their own.
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                continue;
            }
            List<Block> blocks = blocksOf(tab);
            if (blocks.isEmpty()) {
                continue;
            }
            claimed.addAll(blocks);
            Identifier key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            fromTabs.add(new Category(
                    key == null ? tab.getDisplayName().getString() : key.toString(),
                    tab.getDisplayName().getString(),
                    tab.getIconItem(),
                    blocks));
        }

        List<Category> result = new ArrayList<>(fromTabs.stream()
                .sorted(Comparator.comparing(category -> namespaceOf(category.id()).equals(VANILLA) ? "" : namespaceOf(category.id())))
                .toList());
        result.addAll(leftovers(claimed));
        return List.copyOf(result);
    }

    /** The distinct blocks a tab shows, in the order the tab shows them. */
    private static List<Block> blocksOf(CreativeModeTab tab) {
        List<Block> blocks = new ArrayList<>();
        Set<Block> seen = new LinkedHashSet<>();
        for (ItemStack stack : tab.getDisplayItems()) {
            // A tab lists items; most are not placeable, and a block can appear more than once
            // (variants differing only by data components).
            Block block = Block.byItem(stack.getItem());
            if (block != Blocks.AIR && seen.add(block)) {
                blocks.add(block);
            }
        }
        return List.copyOf(blocks);
    }

    /**
     * Blocks no creative tab lists, bucketed by mod. This is not an edge case: wheat, fluids and
     * every other block whose item is not itself placeable lives here, and those are exactly the
     * things a "find" or "harvest" picker needs to offer.
     */
    private static List<Category> leftovers(Set<Block> claimed) {
        Map<String, List<Block>> byNamespace = new LinkedHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (claimed.contains(block) || block.defaultBlockState().isAir()) {
                continue;
            }
            byNamespace.computeIfAbsent(key(block).getNamespace(), namespace -> new ArrayList<>()).add(block);
        }

        return byNamespace.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().equals(VANILLA) ? "" : entry.getKey()))
                .map(entry -> new Category(
                        "lune:other/" + entry.getKey(),
                        modName(entry.getKey()) + " (other)",
                        iconFor(entry.getValue()),
                        List.copyOf(entry.getValue())))
                .toList();
    }

    /** A tab id's namespace is the mod that registered it, which is how tabs cluster per mod. */
    private static String namespaceOf(String categoryId) {
        int colon = categoryId.indexOf(':');
        return colon < 0 ? VANILLA : categoryId.substring(0, colon);
    }

    /** {@code some_mod} reads as "Some Mod". Derived from the id because mod names are loader-specific. */
    private static String modName(String namespace) {
        StringBuilder name = new StringBuilder(namespace.length());
        for (String word : namespace.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return name.isEmpty() ? namespace : name.toString();
    }

    private static ItemStack iconFor(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.asItem() != Items.AIR) {
                return new ItemStack(block.asItem());
            }
        }
        return new ItemStack(Blocks.BARRIER.asItem());
    }

    private static Identifier key(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }
}
