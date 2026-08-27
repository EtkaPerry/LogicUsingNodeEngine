package com.etka.lune.bot.catalog;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A set of explicit blocks and/or block tags, used by Mine/Find/Harvest/Explore/Stripmine.
 * <p>
 * Tags are stored as identifiers and resolved at runtime, so a routine that asks for
 * {@code #minecraft:stone} will automatically include new stone variants added by mods.
 */
public final class BlockTarget {

    private final Set<Block> blocks;
    private final Set<Identifier> tags;

    public BlockTarget(Set<Block> blocks, Set<Identifier> tags) {
        this.blocks = new LinkedHashSet<>(blocks);
        this.tags = new LinkedHashSet<>(tags);
    }

    public static BlockTarget empty() {
        return new BlockTarget(Set.of(), Set.of());
    }

    public static BlockTarget ofBlocks(Set<Block> blocks) {
        return new BlockTarget(blocks, Set.of());
    }

    public static BlockTarget ofTag(Identifier tag) {
        Set<Identifier> tags = new LinkedHashSet<>();
        tags.add(tag);
        return new BlockTarget(Set.of(), tags);
    }

    /** Parses a comma-separated string where tags are prefixed with {@code #}. */
    public static BlockTarget deserialize(String raw) {
        Set<Block> blocks = new LinkedHashSet<>();
        Set<Identifier> tags = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return new BlockTarget(blocks, tags);
        }
        for (String part : raw.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.startsWith("#")) {
                Identifier id = Identifier.tryParse(part.substring(1));
                if (id != null) {
                    tags.add(id);
                }
            } else {
                Identifier id = Identifier.tryParse(part);
                if (id != null) {
                    BuiltInRegistries.BLOCK.getOptional(id).ifPresent(blocks::add);
                }
            }
        }
        return new BlockTarget(blocks, tags);
    }

    public Set<Block> blocks() {
        return Collections.unmodifiableSet(blocks);
    }

    public Set<Identifier> tags() {
        return Collections.unmodifiableSet(tags);
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && tags.isEmpty();
    }

    public BlockTarget withBlock(Block block) {
        Set<Block> b = new LinkedHashSet<>(blocks);
        b.add(block);
        return new BlockTarget(b, tags);
    }

    public BlockTarget withoutBlock(Block block) {
        Set<Block> b = new LinkedHashSet<>(blocks);
        b.remove(block);
        return new BlockTarget(b, tags);
    }

    public BlockTarget withTag(Identifier tag) {
        Set<Identifier> t = new LinkedHashSet<>(tags);
        t.add(tag);
        return new BlockTarget(blocks, t);
    }

    public BlockTarget withoutTag(Identifier tag) {
        Set<Identifier> t = new LinkedHashSet<>(tags);
        t.remove(tag);
        return new BlockTarget(blocks, t);
    }

    /** True if the live block state is one of the explicit blocks or belongs to any stored tag. */
    public boolean matches(BlockState state) {
        if (blocks.contains(state.getBlock())) {
            return true;
        }
        for (Identifier tag : tags) {
            if (state.is(tagKey(tag))) {
                return true;
            }
        }
        return false;
    }

    /** True if the block itself matches the explicit list or any stored tag. */
    public boolean matches(Block block) {
        if (blocks.contains(block)) {
            return true;
        }
        BlockState state = block.defaultBlockState();
        for (Identifier tag : tags) {
            if (state.is(tagKey(tag))) {
                return true;
            }
        }
        return false;
    }

    /** A concrete set of blocks expanded from tags where the tag is currently known. */
    public Set<Block> resolvedBlocks() {
        Set<Block> result = new LinkedHashSet<>(blocks);
        for (Identifier tag : tags) {
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tagKey(tag))) {
                result.add(holder.value());
            }
        }
        return result;
    }

    /** Returns the first concrete block, or the first block from the first known tag, if any. */
    public Optional<Block> anyConcreteBlock() {
        if (!blocks.isEmpty()) {
            return Optional.of(blocks.iterator().next());
        }
        for (Identifier tag : tags) {
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tagKey(tag))) {
                return Optional.of(holder.value());
            }
        }
        return Optional.empty();
    }

    /** Serialises as comma-separated ids, with tags prefixed by {@code #}. */
    public String serialize() {
        String blockPart = blocks.stream()
                .map(b -> BuiltInRegistries.BLOCK.getKey(b).toString())
                .collect(Collectors.joining(","));
        String tagPart = tags.stream()
                .map(t -> "#" + t)
                .collect(Collectors.joining(","));
        if (blockPart.isEmpty()) {
            return tagPart;
        }
        if (tagPart.isEmpty()) {
            return blockPart;
        }
        return blockPart + "," + tagPart;
    }

    private static TagKey<Block> tagKey(Identifier id) {
        return TagKey.create(Registries.BLOCK, id);
    }
}
