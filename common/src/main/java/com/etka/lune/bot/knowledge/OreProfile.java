package com.etka.lune.bot.knowledge;

import com.etka.lune.util.Lang;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the bot knows about a given ore: where it concentrates, what biomes or dimensions
 * change that, and how to behave when it runs into a cave.
 */
public final class OreProfile {

    private final String name;
    private final Set<Block> blocks;
    private final int bestY;
    private final int yMin;
    private final int yMax;
    private final Optional<String> biome;
    private final Optional<String> dimension;
    private final boolean cavePreferred;
    private final String notes;

    public OreProfile(String name, Set<Block> blocks, int bestY, int yMin, int yMax,
                      Optional<String> biome, Optional<String> dimension,
                      boolean cavePreferred, String notes) {
        this.name = name;
        this.blocks = Set.copyOf(blocks);
        this.bestY = bestY;
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
        this.biome = biome;
        this.dimension = dimension;
        this.cavePreferred = cavePreferred;
        this.notes = notes;
    }

    public OreProfile(String name, Set<Block> blocks, int bestY, int yMin, int yMax) {
        this(name, blocks, bestY, yMin, yMax, Optional.empty(), Optional.empty(), false, "");
    }

    public String name() {
        return name;
    }

    public Set<Block> blocks() {
        return blocks;
    }

    public int bestY() {
        return bestY;
    }

    public int yMin() {
        return yMin;
    }

    public int yMax() {
        return yMax;
    }

    public Optional<String> biome() {
        return biome;
    }

    public Optional<String> dimension() {
        return dimension;
    }

    public boolean cavePreferred() {
        return cavePreferred;
    }

    /**
     * Where to dig for this ore, in the player's language.
     *
     * <p>A key for the built-in table, plain text for a profile out of the config file. Resolved
     * here because the table is built in a static initialiser, and resolving it there would fix
     * every note in whatever language happened to be loaded at class-load - see
     * {@link BiomeProfile#notes()}, which had exactly that bug.</p>
     */
    public String notes() {
        return notes.startsWith("lune.") ? Lang.get(notes) : notes;
    }

    /** True when a position is inside the vertical band this profile is describing. */
    public boolean inBand(int y) {
        return y >= yMin && y <= yMax;
    }
}
