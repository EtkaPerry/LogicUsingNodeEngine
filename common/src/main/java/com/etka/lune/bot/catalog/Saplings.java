package com.etka.lune.bot.catalog;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.NetherFungusBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * Which sapling a felled tree grows back from, and where one can go.
 *
 * <p>Asked of the registry rather than listed. A log is called {@code <wood>_log} and its sapling
 * {@code <wood>_sapling} in vanilla and in nearly every mod that adds a tree, so the pair is found by
 * name and a modded forest replants without Lune knowing it exists. The few trees that break the
 * pattern - the mangrove grows from a propagule, the Nether's two from fungi - are the exceptions
 * written down here, and a log with no sapling at all simply answers {@link Items#AIR}.</p>
 *
 * <p>The tags are named here rather than read off {@code BlockTags}, because several of those
 * constants moved between the Minecraft versions this tree builds for while the tags themselves
 * stayed where they were.</p>
 */
public final class Saplings {

    /** Everything that grows into a tree when planted, vanilla's and any mod's that tags its own. */
    private static final TagKey<Block> SAPLINGS =
            TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("saplings"));
    /**
     * The ground a sapling takes root in: the tag vanilla's own saplings ask, so grass, mud, moss
     * and farmland all count. Not {@code dirt}, which stopped including grass in these versions.
     */
    private static final TagKey<Block> ROOTING_GROUND =
            TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("supports_vegetation"));
    /** Where the Nether's fungi grow, which is where their stems stood. */
    private static final TagKey<Block> NYLIUM =
            TagKey.create(Registries.BLOCK, Identifier.withDefaultNamespace("nylium"));

    /** The part of a trunk's id that names the wood rather than the tree. */
    private static final List<String> TRUNK_SUFFIXES = List.of("_log", "_wood", "_stem", "_hyphae");

    /** Trees whose sapling is not called {@code <wood>_sapling}, keyed by the wood. */
    private static final Map<String, String> NAMED_OTHERWISE = Map.of(
            "mangrove", "mangrove_propagule",
            "crimson", "crimson_fungus",
            "warped", "warped_fungus");

    private Saplings() {}

    /**
     * The sapling that grows the tree this log came from, or {@link Items#AIR} when there is none.
     *
     * <p>Stripped logs answer for the tree they were stripped from: a trunk somebody took an axe to
     * still grew from the same sapling.</p>
     */
    public static Item forLog(Block log) {
        if (log == null) {
            return Items.AIR;
        }
        Identifier id = BuiltInRegistries.BLOCK.getKey(log);
        String wood = woodOf(id.getPath());
        if (wood == null) {
            return Items.AIR;
        }
        String named = NAMED_OTHERWISE.get(wood);
        for (String candidate : named == null
                ? List.of(wood + "_sapling", wood + "_propagule", wood + "_fungus")
                : List.of(named, wood + "_sapling")) {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(
                    id.getNamespace(), candidate));
            if (item != null && item != Items.AIR) {
                return item;
            }
        }
        return Items.AIR;
    }

    /** The wood a trunk block is made of - {@code oak} for {@code stripped_oak_log} - or null. */
    static String woodOf(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.startsWith("stripped_") ? path.substring("stripped_".length()) : path;
        for (String suffix : TRUNK_SUFFIXES) {
            if (trimmed.endsWith(suffix) && trimmed.length() > suffix.length()) {
                return trimmed.substring(0, trimmed.length() - suffix.length());
            }
        }
        return null;
    }

    /**
     * Whether a carried stack is something that grows into a tree once planted.
     *
     * <p>The Nether's fungi grow into trees too, and are not in the saplings tag, so they are
     * recognised by what they are.</p>
     */
    public static boolean isSapling(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem held)) {
            return false;
        }
        BlockState state = held.getBlock().defaultBlockState();
        return state.is(SAPLINGS) || held.getBlock() instanceof NetherFungusBlock;
    }

    /**
     * Whether the block a trunk stood on is ground a tree could be planted back into.
     *
     * <p>Asked when a tree comes down rather than when it is replanted, so the note Chop Wood takes
     * only ever names places that were worth noting. Whether a particular sapling will actually take
     * there is asked again at planting time, of that sapling.</p>
     */
    public static boolean canRoot(BlockState ground) {
        return ground != null && (ground.is(ROOTING_GROUND) || ground.is(NYLIUM));
    }
}
