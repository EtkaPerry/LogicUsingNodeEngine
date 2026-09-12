package com.etka.lune.bot.knowledge;

import com.etka.lune.util.Lang;

/**
 * What a biome is worth to the bot, per {@link Need}.
 * <p>
 * Availability values run 0 (this biome will never give you that) to 1 (this is where you go for
 * it). {@code travelCost} is a multiplier on how expensive the ground is to cross - 1 is open
 * walkable land, an ocean is around 3 because swimming it is slow and dangerous.
 *
 * @param notes why the numbers are what they are; read out when Lune explains a direction choice.
 *              A key for the built-in table, plain text for a profile out of the config file.
 */
public record BiomeProfile(String name, float wood, float food, float stone, float sand, float water,
                           float travelCost, String notes) {

    /** Neutral profile for a biome nothing is known about - never excludes it, never favours it. */
    public static final BiomeProfile UNKNOWN =
            new BiomeProfile("unknown", 0.5F, 0.5F, 0.4F, 0.2F, 0.2F, 1.0F, "lune.biome.unfamiliar");

    /**
     * The note in the player's language.
     *
     * <p>Resolved here rather than where the table is built, because the table is built in a
     * static initialiser: doing it there fixes every note in whatever language happened to be
     * loaded at class-load and leaves them there for the rest of the session, however many
     * times the player changes language afterwards.</p>
     *
     * <p>A note out of the config file is the player's own wording and is passed through.</p>
     */
    public String notes() {
        return notes.startsWith("lune.") ? Lang.get(notes) : notes;
    }

    public float availability(Need need) {
        return switch (need) {
            case WOOD -> wood;
            case FOOD -> food;
            case STONE -> stone;
            case SAND -> sand;
            case WATER -> water;
            case ANY -> 0.5F;
        };
    }

    /** True when this biome is a dead end for the need - the "no trees in the mesa" test. */
    public boolean isBarrenFor(Need need) {
        return availability(need) <= 0.1F;
    }
}
