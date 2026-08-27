package com.etka.lune.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain config POJO for players who want to override or extend the bot's ore-layer knowledge.
 * The key in {@link BotConfig#oreProfiles} is a human-readable name; {@code blocks} lists the
 * registry IDs this profile applies to.
 */
public final class OreProfileData {

    public List<String> blocks = new ArrayList<>();
    public int bestY;
    public int yMin;
    public int yMax;
    public String biome;
    public String dimension;
    public boolean cavePreferred;
    public String notes = "";
}
