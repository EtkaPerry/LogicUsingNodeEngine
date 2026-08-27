package com.etka.lune.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain config POJO for players who want to teach the bot about biomes it does not know - a modded
 * biome, or a vanilla one they disagree with. The key in {@link BotConfig#biomeProfiles} is a
 * human-readable name; {@code biomes} lists the biome registry IDs this profile applies to.
 * <p>
 * Availability fields run 0 (never found here) to 1 (this is where you go for it).
 */
public final class BiomeProfileData {

    public List<String> biomes = new ArrayList<>();
    public float wood;
    public float food;
    public float stone;
    public float sand;
    public float water;
    /** Multiplier on how expensive the ground is to cross. 1 is open land; an ocean is about 3. */
    public float travelCost = 1.0F;
    public String notes = "";
}
