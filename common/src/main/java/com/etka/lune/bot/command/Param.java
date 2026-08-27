package com.etka.lune.bot.command;

import com.etka.lune.bot.catalog.BlockTarget;
import com.etka.lune.bot.util.InventoryHelper;
import com.etka.lune.util.Coordinates;
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
        NUMBER, BOOLEAN, CHOICE, ITEM, BLOCK_SET, ENTITY_SET, POSITION
    }

    private final String id;
    private final String label;
    private final String tooltip;
    protected T value;

    protected Param(String id, String label, String tooltip, T initial) {
        this.id = id;
        this.label = label;
        this.tooltip = tooltip;
        this.value = initial;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String tooltip() {
        return tooltip;
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

        public Ints(String id, String label, String tooltip, int initial, int min, int max) {
            super(id, label, tooltip, initial);
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
        public Bool(String id, String label, String tooltip, boolean initial) {
            super(id, label, tooltip, initial);
        }

        public void toggle() {
            set(!get());
        }

        @Override
        public String displayValue() {
            return get() ? "Yes" : "No";
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

        public Choice(String id, String label, String tooltip, List<String> options, String initial) {
            this(id, label, tooltip, () -> options, initial);
        }

        public Choice(String id, String label, String tooltip, Supplier<List<String>> options, String initial) {
            super(id, label, tooltip, initial);
            this.options = options;
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

        @Override
        public String displayValue() {
            String value = get();
            if (value == null || value.isEmpty()) {
                return options().isEmpty() ? "none available" : "not set";
            }
            return value;
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

    /** Any number of blocks and/or tags - Mine's ore picker. */
    public static final class BlockSet extends Param<BlockTarget> {
        private final List<Block> candidates;
        private final List<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>> tagCandidates;

        public BlockSet(String id, String label, String tooltip, List<Block> candidates, Set<Block> initial) {
            super(id, label, tooltip, BlockTarget.ofBlocks(new LinkedHashSet<>(initial)));
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
                return blockCount == 0 ? "none" : blockCount + " selected";
            }
            if (blockCount == 0) {
                return tagCount + " tag" + (tagCount == 1 ? "" : "s");
            }
            return blockCount + " + " + tagCount + " tags";
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

        public EntitySet(String id, String label, String tooltip, List<EntityType<?>> candidates,
                         Set<EntityType<?>> initial) {
            super(id, label, tooltip, new LinkedHashSet<>(initial));
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
            return count == 0 ? "none" : count + " selected";
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

        public ItemChoice(String id, String label, String tooltip, List<Item> candidates, Item initial) {
            super(id, label, tooltip, candidates.contains(initial) ? initial
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
            return get() == null ? "none available" : InventoryHelper.itemName(get());
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

    /** A world coordinate, with the UI offering a "use my position" shortcut. */
    public static final class Pos extends Param<BlockPos> {
        public Pos(String id, String label, String tooltip, BlockPos initial) {
            super(id, label, tooltip, initial);
        }

        @Override
        public String displayValue() {
            BlockPos pos = get();
            return pos == null ? "not set" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
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
