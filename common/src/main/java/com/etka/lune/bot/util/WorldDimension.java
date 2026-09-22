package com.etka.lune.bot.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Which of the three worlds the bot is standing in, as a Check Dimension card asks about it.
 *
 * <p>A card of its own for the same reason as {@link Weather}: a dimension is a name, not a
 * number, and the six numeric comparisons have nothing useful to say about one.</p>
 *
 * <p>Only the three vanilla dimensions are offered. A pack's own world is reachable as the Fail
 * edge of "Overworld" - the question players ask of a modded dimension is almost always "am I
 * still at home?" - and a dropdown built from whatever the server happened to send would list
 * different options in every world, including none at all before the bot has joined one.</p>
 *
 * <p>The labels are identifiers written into saved tasks, so they stay English and only their
 * rendering is translated - the same rule every dropdown value here follows.</p>
 */
public enum WorldDimension {

    OVERWORLD("Overworld", Level.OVERWORLD),
    NETHER("Nether", Level.NETHER),
    END("End", Level.END);

    private final String label;
    private final ResourceKey<Level> key;

    WorldDimension(String label, ResourceKey<Level> key) {
        this.label = label;
        this.key = key;
    }

    public String label() {
        return label;
    }

    /** Names for a picker, in the order a run visits them. */
    public static List<String> labels() {
        return List.of(OVERWORLD.label, NETHER.label, END.label);
    }

    /** Reads back a name from {@link #labels()}; null when a stored task names something else. */
    public static WorldDimension fromLabel(String label) {
        for (WorldDimension dimension : values()) {
            if (dimension.label.equalsIgnoreCase(label)) {
                return dimension;
            }
        }
        return null;
    }

    /** Whether this is the dimension the bot is in. */
    public boolean matches(ResourceKey<Level> dimension) {
        return key.equals(dimension);
    }

    /**
     * Which of the three a world id names, or null for anything else.
     *
     * <p>For the places that hold a world as the id a person would write - an ore profile in
     * {@code lune.json}, the built-in table's {@code minecraft:the_nether} - rather than as a key
     * they got from the game.</p>
     */
    public static WorldDimension byId(String id) {
        for (WorldDimension known : values()) {
            if (known.key.identifier().toString().equals(id)) {
                return known;
            }
        }
        return null;
    }

    /** Which of the three this is, or null in a dimension some other mod added. */
    public static WorldDimension of(ResourceKey<Level> dimension) {
        for (WorldDimension known : values()) {
            if (known.matches(dimension)) {
                return known;
            }
        }
        return null;
    }
}
