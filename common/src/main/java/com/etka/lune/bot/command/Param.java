package com.etka.lune.bot.command;

import com.etka.lune.bot.catalog.BlockTarget;
import com.etka.lune.bot.catalog.CraftRecipe;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.util.Coordinates;
import com.etka.lune.util.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * A typed, self-describing input for a command.
 * <p>
 * This is the piece that makes the Tasks palette generic: the UI renders whatever parameters a command
 * declares, so adding a new command is a registry entry and a {@link com.etka.lune.bot.Task} -
 * never a GUI change.
 */
public abstract class Param<T> {

    /** Runtime-compatible kinds used to keep exposed data wires meaningful. */
    public enum DataType {
        NUMBER, BOOLEAN, CHOICE, TEXT, ITEM, BLOCK_SET, ENTITY_SET, POSITION, RECIPE
    }

    private final String id;
    /** The command this parameter belongs to, which is half of its translation key. */
    private String owner = "";
    protected T value;

    protected Param(String id, T initial) {
        this.id = id;
        this.value = initial;
    }

    /**
     * Told to it by the command that declares it.
     *
     * <p>A parameter is only ever unique within its own command - half the commands in the
     * palette have a "radius" - so the key needs both halves, and only the command knows
     * which one it is.</p>
     */
    void attachTo(String owner) {
        this.owner = owner == null ? "" : owner;
    }

    public String id() {
        return id;
    }

    public String label() {
        return text(".label");
    }

    public String tooltip() {
        return text(".tip");
    }

    /**
     * This card's wording for the parameter, or the one every card shares.
     *
     * <p>Eight cards have a "Search radius" and they all call it the same thing. Writing that
     * line out once per card is eight lines for a translator to translate identically and seven
     * chances to translate one of them differently, so the shared
     * {@code lune.param.radius.label} carries it and a card only writes its own when it genuinely
     * means something else by it.</p>
     */
    protected String text(String suffix) {
        String own = "lune.command." + owner + ".param." + id + suffix;
        return Lang.has(own) ? Lang.get(own) : Lang.get("lune.param." + id + suffix);
    }

    public T get() {
        return value;
    }

    /** The kind of value this parameter serialises and can exchange through a data port. */
    public DataType dataType() {
        if (this instanceof Ints) {
            return DataType.NUMBER;
        }
        if (this instanceof Bool) {
            return DataType.BOOLEAN;
        }
        if (this instanceof Choice) {
            return DataType.CHOICE;
        }
        if (this instanceof ItemChoice) {
            return DataType.ITEM;
        }
        if (this instanceof BlockSet) {
            return DataType.BLOCK_SET;
        }
        if (this instanceof EntitySet) {
            return DataType.ENTITY_SET;
        }
        if (this instanceof Pos) {
            return DataType.POSITION;
        }
        if (this instanceof Recipe) {
            return DataType.RECIPE;
        }
        if (this instanceof Text) {
            return DataType.TEXT;
        }
        throw new IllegalStateException("Unknown parameter type: " + getClass().getName());
    }

    public void set(T value) {
        this.value = value;
    }

    /** How the current value reads in the UI. */
    public abstract String displayValue();

    /**
     * The value as a portable string. Registry-backed parameters serialise as namespaced ids
     * ({@code minecraft:iron_ore}), never as registry indices - that is what lets a task survive
     * a different mod set, and lets one player import another's task.
     */
    public abstract String serialize();

    /**
     * Restores a value produced by {@link #serialize}. Implementations must tolerate junk: an
     * imported task may reference blocks or mods this instance doesn't have, and the sane
     * response is to drop the unknown entries rather than reject the whole task.
     */
    public abstract void deserialize(String raw);

    /** A whole number within an inclusive range. */
    public static final class Ints extends Param<Integer> {
        private final int min;
        private final int max;

        public Ints(String id, int initial, int min, int max) {
            super(id, initial);
            this.min = min;
            this.max = max;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }

        @Override
        public void set(Integer value) {
            super.set(Math.clamp(value, min, max));
        }

        @Override
        public String displayValue() {
            return String.valueOf(get());
        }

        @Override
        public String serialize() {
            return String.valueOf(get());
        }

        @Override
        public void deserialize(String raw) {
            try {
                set(Integer.parseInt(raw.trim()));
            } catch (NumberFormatException e) {
                // Leave the default in place rather than failing the whole import.
            }
        }
    }

    /** An on/off toggle. */
    public static final class Bool extends Param<Boolean> {
        public Bool(String id, boolean initial) {
            super(id, initial);
        }

        public void toggle() {
            set(!get());
        }

        @Override
        public String displayValue() {
            return Lang.get(get() ? "lune.gui.param.yes" : "lune.gui.param.no");
        }

        @Override
        public String serialize() {
            return String.valueOf(get());
        }

        @Override
        public void deserialize(String raw) {
            set(Boolean.parseBoolean(raw.trim()));
        }
    }

    /**
     * One option from a list. The list is supplied lazily so it can change at runtime - that's what
     * lets a "which waypoint" parameter offer whatever the player has saved right now.
     */
    public static final class Choice extends Param<String> {
        private final Supplier<List<String>> options;
        /**
         * How this choice's values are written on screen.
         *
         * <p>Nearly always the shared {@code lune.choice.*} lookup. A choice whose values are not
         * words - language codes, say - hands in its own, because "tr_tr" is a fine thing to store
         * in a config file and a poor thing to show somebody.</p>
         */
        private final UnaryOperator<String> labels;
        private final boolean ownLabels;

        public Choice(String id, List<String> options, String initial) {
            this(id, () -> options, initial);
        }

        public Choice(String id, Supplier<List<String>> options, String initial) {
            super(id, initial);
            this.options = options;
            // Names the player wrote - a waypoint, a task - are shown as typed. Everything else
            // goes through the shared lookup, which is what the language file is checked against.
            this.ownLabels = "waypoint".equals(id) || "name".equals(id);
            this.labels = ownLabels ? UnaryOperator.identity() : Choice::optionLabel;
        }

        public Choice(String id, Supplier<List<String>> options, String initial,
                      UnaryOperator<String> labels) {
            super(id, initial);
            this.options = options;
            this.labels = labels;
            this.ownLabels = true;
        }

        /**
         * Whether this choice writes its own labels instead of using {@code lune.choice.*}.
         *
         * <p>For the check that every dropdown value has a line to be drawn from: a choice that
         * asks an item what it is called, or shows a name the player typed, has no key to find and
         * wants none. Saying so here beats the check keeping a list of which ids to skip, which
         * went stale the moment a fifth one appeared.</p>
         */
        public boolean labelsItsOwnOptions() {
            return ownLabels;
        }

        /** What one of this choice's values is called on screen. */
        public String label(String value) {
            return value == null || value.isEmpty() ? "" : labels.apply(value);
        }

        public List<String> options() {
            return options.get();
        }

        /** Advances to the next option, wrapping - the behaviour of a vanilla cycle button. */
        public void cycle() {
            List<String> current = options();
            if (current.isEmpty()) {
                return;
            }
            int index = current.indexOf(get());
            set(current.get((index + 1) % current.size()));
        }

        /**
         * What a choice is called, as opposed to what it is.
         *
         * <p>The value is an identifier: it goes into saved tasks and is compared against by the
         * cards that read it. Translating it would rewrite every task on disk the first time
         * somebody changed language. Translating what is drawn costs nothing and breaks nothing,
         * and an option nobody has written a key for still reads as the English it always was.</p>
         */
        public static String optionLabel(String value) {
            if (value == null || value.isEmpty()) {
                return "";
            }
            return Lang.getOr("lune.choice."
                    + value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                            .replaceAll("^_+|_+$", ""), value);
        }

        @Override
        public String displayValue() {
            String value = get();
            if (value == null || value.isEmpty()) {
                return options().isEmpty()
                        ? Lang.get("lune.gui.param.none_available")
                        : Lang.get("lune.gui.param.not_set");
            }
            return label(value);
        }

        @Override
        public String serialize() {
            return get() == null ? "" : get();
        }

        @Override
        public void deserialize(String raw) {
            // Kept even when it isn't currently a valid option: a waypoint named in an imported
            // task may simply not exist yet, and silently blanking it hides that.
            set(raw);
        }
    }

    /**
     * A short line the player types.
     * <p>
     * The one input that cannot be a list, because it names something that does not exist yet: a
     * waypoint for a place the bot has not been to. Everything else in the palette picks from what
     * the game or the player already has, which is why this is the only free-text parameter.
     */
    public static final class Text extends Param<String> {
        private final int maxLength;

        public Text(String id, String initial, int maxLength) {
            super(id, initial == null ? "" : initial);
            this.maxLength = Math.max(1, maxLength);
        }

        public int maxLength() {
            return maxLength;
        }

        /**
         * A short placeholder for the empty field. The tooltip is the long explanation.
         *
         * <p>Derived from the id like the label and the tooltip are, rather than passed in.
         * The registry builds its cards in a static initialiser, so a hint written there would
         * be resolved once, in whatever language happened to be loaded first, and then never
         * again.</p>
         */
        public String hint() {
            return text(".hint");
        }

        @Override
        public void set(String value) {
            String trimmed = value == null ? "" : value.strip();
            super.set(trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed);
        }

        @Override
        public String displayValue() {
            return get().isBlank() ? Lang.get("lune.gui.param.not_set") : get();
        }

        @Override
        public String serialize() {
            return get();
        }

        @Override
        public void deserialize(String raw) {
            set(raw);
        }
    }

    /** Any number of blocks and/or tags - Mine's ore picker. */
    public static final class BlockSet extends Param<BlockTarget> {
        private final List<Block> candidates;
        private final List<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>> tagCandidates;

        public BlockSet(String id, List<Block> candidates, Set<Block> initial) {
            super(id, BlockTarget.ofBlocks(new LinkedHashSet<>(initial)));
            this.candidates = List.copyOf(candidates);
            this.tagCandidates = BuiltInRegistries.BLOCK.getTags()
                    .flatMap(holderSet -> holderSet.unwrapKey().stream())
                    .sorted(Comparator.comparing(tag -> tag.location().toString()))
                    .toList();
        }

        public List<Block> candidates() {
            return candidates;
        }

        public List<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>> tagCandidates() {
            return tagCandidates;
        }

        public boolean isSelected(Block block) {
            return get().blocks().contains(block);
        }

        public boolean isSelected(net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag) {
            return get().tags().contains(tag.location());
        }

        public void toggle(Block block) {
            Set<Block> b = new LinkedHashSet<>(get().blocks());
            Set<net.minecraft.resources.Identifier> t = new LinkedHashSet<>(get().tags());
            if (!b.remove(block)) {
                b.add(block);
            }
            set(new BlockTarget(b, t));
        }

        public void toggle(net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tag) {
            Set<Block> b = new LinkedHashSet<>(get().blocks());
            Set<net.minecraft.resources.Identifier> t = new LinkedHashSet<>(get().tags());
            if (!t.remove(tag.location())) {
                t.add(tag.location());
            }
            set(new BlockTarget(b, t));
        }

        @Override
        public String displayValue() {
            int blockCount = get().blocks().size();
            int tagCount = get().tags().size();
            if (tagCount == 0) {
                return blockCount == 0 ? Lang.get("lune.gui.param.none") : Lang.get("lune.gui.param.selected_count", blockCount);
            }
            if (blockCount == 0) {
                return Lang.get("lune.gui.param.tag_count", tagCount);
            }
            return Lang.get("lune.gui.param.blocks_and_tags", blockCount, tagCount);
        }

        @Override
        public String serialize() {
            return get().serialize();
        }

        @Override
        public void deserialize(String raw) {
            set(BlockTarget.deserialize(raw));
        }
    }

    /** Any number of entity types - Kill's mob picker. */
    public static final class EntitySet extends Param<Set<EntityType<?>>> {
        private final List<EntityType<?>> candidates;

        public EntitySet(String id, List<EntityType<?>> candidates,
                         Set<EntityType<?>> initial) {
            super(id, new LinkedHashSet<>(initial));
            this.candidates = List.copyOf(candidates);
        }

        public List<EntityType<?>> candidates() {
            return candidates;
        }

        public boolean isSelected(EntityType<?> type) {
            return get().contains(type);
        }

        public void toggle(EntityType<?> type) {
            if (!get().remove(type)) {
                get().add(type);
            }
        }

        @Override
        public String displayValue() {
            int count = get().size();
            return count == 0 ? Lang.get("lune.gui.param.none") : Lang.get("lune.gui.param.selected_count", count);
        }

        @Override
        public String serialize() {
            return get().stream()
                    .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString())
                    .collect(Collectors.joining(","));
        }

        @Override
        public void deserialize(String raw) {
            Set<EntityType<?>> restored = new LinkedHashSet<>();
            for (String id : raw.split(",")) {
                if (id.isBlank()) {
                    continue;
                }
                Identifier key = Identifier.tryParse(id.trim());
                if (key != null) {
                    BuiltInRegistries.ENTITY_TYPE.getOptional(key).ifPresent(restored::add);
                }
            }
            set(restored);
        }
    }

    /** One registered inventory item, stored by namespaced id for portable tasks. */
    public static final class ItemChoice extends Param<Item> {
        private final List<Item> candidates;

        public ItemChoice(String id, List<Item> candidates, Item initial) {
            super(id, candidates.contains(initial) ? initial
                    : (candidates.isEmpty() ? null : candidates.get(0)));
            this.candidates = List.copyOf(candidates);
        }

        public List<Item> candidates() {
            return candidates;
        }

        /** Advances through the live item catalog, wrapping at the end. */
        public void cycle() {
            if (candidates.isEmpty()) {
                return;
            }
            int index = candidates.indexOf(get());
            set(candidates.get((index + 1) % candidates.size()));
        }

        @Override
        public String displayValue() {
            return get() == null ? Lang.get("lune.gui.param.none_available") : InventoryHelper.itemName(get());
        }

        @Override
        public String serialize() {
            return get() == null ? "" : BuiltInRegistries.ITEM.getKey(get()).toString();
        }

        @Override
        public void deserialize(String raw) {
            Identifier key = Identifier.tryParse(raw.trim());
            if (key != null) {
                BuiltInRegistries.ITEM.getOptional(key).ifPresent(this::set);
            }
        }
    }

    /**
     * What to craft: an item named from the recipe book, or a grid the player draws themselves.
     * <p>
     * The one parameter that can be a picture rather than a name, and the reason it is a single
     * parameter: "which recipe?" is one question, and splitting it into a mode switch, an item and
     * a grid made the card three rows longer without answering it any better.
     */
    public static final class Recipe extends Param<CraftRecipe> {

        public Recipe(String id) {
            super(id, CraftRecipe.empty());
        }

        @Override
        public String displayValue() {
            return get().describe();
        }

        @Override
        public String serialize() {
            return get().serialize();
        }

        @Override
        public void deserialize(String raw) {
            set(CraftRecipe.deserialize(raw));
        }
    }

    /** A world coordinate, with the UI offering a "use my position" shortcut. */
    public static final class Pos extends Param<BlockPos> {
        public Pos(String id, BlockPos initial) {
            super(id, initial);
        }

        @Override
        public String displayValue() {
            BlockPos pos = get();
            return pos == null ? Lang.get("lune.gui.param.not_set") : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        }

        @Override
        public String serialize() {
            return Coordinates.format(get());
        }

        @Override
        public void deserialize(String raw) {
            set(Coordinates.parse(raw));
        }
    }
}
