package com.etka.lune.bot.catalog;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The hand tools the bot knows how to make: one ladder per tool kind, ordered by harvest tier, plus
 * what each one costs to craft.
 * <p>
 * Keeping the ladder in one place is what lets a request be answered by something better instead of
 * remade - asking for a stone axe while a diamond axe is already in the bag is already satisfied.
 * Gold sits beside wood on purpose: golden tools cut quickly but harvest at wood's level, so a
 * golden pickaxe is not an answer to a request for a stone one.
 * <p>
 * Netherite and gold are on the ladder so a carried one counts, but neither is offered as something
 * to go and make. Netherite needs a smithing template and ancient debris, and gold ore needs an iron
 * pickaxe to mine - both are longer errands than the better tool they would replace.
 */
public final class ToolCatalog {

    /** What a tool is for. */
    public enum Kind {
        PICKAXE("Pickaxe"), AXE("Axe"), SHOVEL("Shovel"), SWORD("Sword"), HOE("Hoe");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * What a tool is cut from, which is also what has to be gathered before it can be made. The
     * harvest level is vanilla's, which is why gold shares one with wood.
     */
    public enum Material {
        WOOD("Wooden", 0), GOLD("Golden", 0), STONE("Stone", 1), IRON("Iron", 2),
        DIAMOND("Diamond", 3), NETHERITE("Netherite", 4);

        private final String label;
        private final int harvestLevel;

        Material(String label, int harvestLevel) {
            this.label = label;
            this.harvestLevel = harvestLevel;
        }

        public String label() {
            return label;
        }
    }

    /** One kind of tool at every material, so a tool in the bag can be placed on the ladder. */
    private record Ladder(Kind kind, Item wood, Item gold, Item stone, Item iron, Item diamond,
                          Item netherite) {

        Item of(Material material) {
            return switch (material) {
                case WOOD -> wood;
                case GOLD -> gold;
                case STONE -> stone;
                case IRON -> iron;
                case DIAMOND -> diamond;
                case NETHERITE -> netherite;
            };
        }

        Material materialOf(Item item) {
            for (Material material : Material.values()) {
                if (of(material) == item) {
                    return material;
                }
            }
            return null;
        }
    }

    private static final List<Ladder> LADDERS = List.of(
            new Ladder(Kind.PICKAXE, Items.WOODEN_PICKAXE, Items.GOLDEN_PICKAXE, Items.STONE_PICKAXE,
                    Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE),
            new Ladder(Kind.AXE, Items.WOODEN_AXE, Items.GOLDEN_AXE, Items.STONE_AXE,
                    Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE),
            new Ladder(Kind.SHOVEL, Items.WOODEN_SHOVEL, Items.GOLDEN_SHOVEL, Items.STONE_SHOVEL,
                    Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL),
            new Ladder(Kind.SWORD, Items.WOODEN_SWORD, Items.GOLDEN_SWORD, Items.STONE_SWORD,
                    Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD),
            new Ladder(Kind.HOE, Items.WOODEN_HOE, Items.GOLDEN_HOE, Items.STONE_HOE,
                    Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE));

    /** The materials the tool chain can gather for itself, cheapest first. */
    private static final List<Material> GATHERABLE = List.of(
            Material.WOOD, Material.STONE, Material.IRON, Material.DIAMOND);

    private ToolCatalog() {}

    /** The vanilla tool of this kind and material, e.g. {@code AXE + STONE} is a stone axe. */
    public static Item item(Kind kind, Material material) {
        for (Ladder ladder : LADDERS) {
            if (ladder.kind() == kind) {
                return ladder.of(material);
            }
        }
        return null;
    }

    /** What this tool is for, or null when it is not a tool the catalog knows. */
    public static Kind kindOf(Item item) {
        for (Ladder ladder : LADDERS) {
            if (ladder.materialOf(item) != null) {
                return ladder.kind();
            }
        }
        return null;
    }

    /** What this tool is cut from, or null when it is not a tool the catalog knows. */
    public static Material materialOf(Item item) {
        for (Ladder ladder : LADDERS) {
            Material material = ladder.materialOf(item);
            if (material != null) {
                return material;
            }
        }
        return null;
    }

    /** Whether the tool chain can make this tool from raw materials at all. */
    public static boolean canMake(Item tool) {
        return GATHERABLE.contains(materialOf(tool));
    }

    /**
     * Whether a carried tool answers a request for another one: same kind, and no worse at getting
     * drops. This is what stops "get a stone axe" throwing away the iron one already in the bag.
     */
    public static boolean satisfies(Item held, Item wanted) {
        Kind kind = kindOf(wanted);
        if (kind == null || kindOf(held) != kind) {
            return false;
        }
        Material heldMaterial = materialOf(held);
        Material wantedMaterial = materialOf(wanted);
        return heldMaterial != null && wantedMaterial != null
                && heldMaterial.harvestLevel >= wantedMaterial.harvestLevel;
    }

    /** How much of its material one tool costs: 3 for a pickaxe or axe, 2 for a sword or hoe, 1 for a shovel. */
    public static int materialCount(Kind kind) {
        return switch (kind) {
            case PICKAXE, AXE -> 3;
            case SWORD, HOE -> 2;
            case SHOVEL -> 1;
        };
    }

    /** How many sticks one tool costs. Only a sword makes do with one. */
    public static int sticks(Kind kind) {
        return kind == Kind.SWORD ? 1 : 2;
    }

    /**
     * What one unit of a material looks like in the bag. Wood and stone are asked for as tags: any
     * plank makes a wooden tool, and cobbled deepslate or blackstone makes a stone one just as
     * cobblestone does.
     */
    public static Predicate<ItemStack> material(Material material) {
        return switch (material) {
            case WOOD -> stack -> stack.is(ItemTags.PLANKS);
            case STONE -> stack -> stack.is(ItemTags.STONE_TOOL_MATERIALS);
            case GOLD -> stack -> stack.is(Items.GOLD_INGOT);
            case IRON -> stack -> stack.is(Items.IRON_INGOT);
            case DIAMOND -> stack -> stack.is(Items.DIAMOND);
            // Never gathered - netherite tools are upgraded, not crafted - but the ladder is
            // exhaustive so that a new material cannot be added without deciding what it costs.
            case NETHERITE -> stack -> stack.is(Items.NETHERITE_INGOT);
        };
    }

    /** Tool names for a picker, in ladder order. */
    public static List<String> kindNames() {
        List<String> names = new ArrayList<>();
        for (Ladder ladder : LADDERS) {
            names.add(ladder.kind().label());
        }
        return List.copyOf(names);
    }

    /** Material names for a picker, limited to what the chain can actually go and gather. */
    public static List<String> materialNames() {
        List<String> names = new ArrayList<>();
        for (Material material : GATHERABLE) {
            names.add(material.label());
        }
        return List.copyOf(names);
    }

    /** Reads back a name from {@link #kindNames()}; null when a stored task names something else. */
    public static Kind parseKind(String label) {
        for (Kind kind : Kind.values()) {
            if (kind.label().equalsIgnoreCase(label)) {
                return kind;
            }
        }
        return null;
    }

    /** Reads back a name from {@link #materialNames()}; null when a stored task names something else. */
    public static Material parseMaterial(String label) {
        for (Material material : GATHERABLE) {
            if (material.label().equalsIgnoreCase(label)) {
                return material;
            }
        }
        return null;
    }
}
