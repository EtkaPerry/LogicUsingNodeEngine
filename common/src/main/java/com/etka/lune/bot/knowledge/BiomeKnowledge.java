package com.etka.lune.bot.knowledge;

import com.etka.lune.config.BiomeProfileData;
import com.etka.lune.config.BotConfig;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the bot knows about biomes, so it searches like a player instead of a lawnmower.
 * <p>
 * The point of this class is the judgement a human makes without thinking: you do not walk into a
 * badlands looking for wood, and you do not swim an ocean to reach a tree when there is a forest
 * behind you. {@link BiomeScout} turns these ratings into a heading; this class only holds the
 * facts.
 * <p>
 * Lookup runs most-specific first - a player override, then the built-in vanilla table, then biome
 * tags (which is what makes modded biomes work), then a climate guess. That ordering is why an
 * unknown modded forest still reads as "has wood" rather than falling to a useless default.
 */
public final class BiomeKnowledge {

    /** Exact-ID knowledge, for the biomes whose tags do not tell the whole story. */
    private static final Map<String, BiomeProfile> VANILLA = new LinkedHashMap<>();
    /** Player overrides from the config, checked ahead of everything built in. */
    private static final Map<String, BiomeProfile> OVERRIDES = new LinkedHashMap<>();
    /** Resolved-profile cache; scouting samples the same handful of biomes over and over. */
    private static final Map<String, BiomeProfile> CACHE = new HashMap<>();

    private static boolean configLoaded = false;

    private BiomeKnowledge() {}

    static {
        registerVanilla();
    }

    /** The profile for a biome, never null. */
    public static BiomeProfile profile(Holder<Biome> biome) {
        if (biome == null) {
            return BiomeProfile.UNKNOWN;
        }
        ensureConfigLoaded();

        String id = biome.unwrapKey().map(key -> key.identifier().toString()).orElse("");
        BiomeProfile cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }

        BiomeProfile resolved = resolve(biome, id);
        if (!id.isEmpty()) {
            CACHE.put(id, resolved);
        }
        return resolved;
    }

    /**
     * How promising a biome is for a set of needs, 0 to 1.
     * <p>
     * The worst need dominates rather than the average: a biome that has stone but no wood is no
     * good to someone who came for both, and averaging would hide that.
     */
    public static float score(Holder<Biome> biome, Set<Need> needs) {
        BiomeProfile profile = profile(biome);
        if (needs == null || needs.isEmpty()) {
            return 0.5F;
        }
        float worst = 1.0F;
        for (Need need : needs) {
            worst = Math.min(worst, profile.availability(need));
        }
        return worst;
    }

    /** True when a biome cannot supply any of the needs - the reason to not walk that way at all. */
    public static boolean isBarrenFor(Holder<Biome> biome, Set<Need> needs) {
        BiomeProfile profile = profile(biome);
        for (Need need : needs) {
            if (!profile.isBarrenFor(need)) {
                return false;
            }
        }
        return !needs.isEmpty();
    }

    /** Short name for status lines: "badlands", "forest". */
    public static String name(Holder<Biome> biome) {
        if (biome == null) {
            return "unknown";
        }
        return biome.unwrapKey()
                .map(key -> key.identifier().getPath().replace('_', ' '))
                .orElse("unknown");
    }

    /** Applies player overrides. Safe to call more than once; the resolved cache is dropped. */
    public static synchronized void loadConfig(Map<String, BiomeProfileData> config) {
        configLoaded = true;
        OVERRIDES.clear();
        CACHE.clear();
        if (config == null || config.isEmpty()) {
            return;
        }
        for (Map.Entry<String, BiomeProfileData> entry : config.entrySet()) {
            BiomeProfileData data = entry.getValue();
            if (data == null || data.biomes == null) {
                continue;
            }
            BiomeProfile profile = new BiomeProfile(entry.getKey(), data.wood, data.food, data.stone,
                    data.sand, data.water, data.travelCost <= 0 ? 1.0F : data.travelCost,
                    data.notes == null ? "" : data.notes);
            for (String id : data.biomes) {
                Identifier parsed = Identifier.tryParse(id.trim());
                if (parsed != null) {
                    OVERRIDES.put(parsed.toString(), profile);
                }
            }
        }
    }

    private static void ensureConfigLoaded() {
        if (!configLoaded) {
            loadConfig(BotConfig.get().biomeProfiles);
        }
    }

    private static BiomeProfile resolve(Holder<Biome> biome, String id) {
        BiomeProfile override = OVERRIDES.get(id);
        if (override != null) {
            return override;
        }
        BiomeProfile known = VANILLA.get(id);
        if (known != null) {
            return known;
        }
        BiomeProfile byTag = fromTags(biome);
        return byTag != null ? byTag : fromClimate(biome);
    }

    /** Tag fallback - this is what lets a modded forest still read as somewhere to find wood. */
    private static BiomeProfile fromTags(Holder<Biome> biome) {
        if (is(biome, BiomeTags.IS_DEEP_OCEAN)) {
            return new BiomeProfile("deep ocean", 0.0F, 0.1F, 0.0F, 0.1F, 1.0F, 3.5F, "open water, nothing grows");
        }
        if (is(biome, BiomeTags.IS_OCEAN)) {
            return new BiomeProfile("ocean", 0.0F, 0.15F, 0.0F, 0.2F, 1.0F, 3.0F, "open water, nothing grows");
        }
        if (is(biome, BiomeTags.IS_RIVER)) {
            return new BiomeProfile("river", 0.2F, 0.3F, 0.1F, 0.4F, 1.0F, 1.5F, "water, banks may have trees");
        }
        if (is(biome, BiomeTags.IS_BADLANDS)) {
            return new BiomeProfile("badlands", 0.0F, 0.05F, 0.85F, 0.8F, 0.05F, 1.3F, "no trees grow here");
        }
        if (is(biome, BiomeTags.IS_JUNGLE)) {
            return new BiomeProfile("jungle", 1.0F, 0.7F, 0.2F, 0.0F, 0.3F, 1.4F, "dense wood, slow going");
        }
        if (is(biome, BiomeTags.IS_FOREST)) {
            return new BiomeProfile("forest", 0.95F, 0.6F, 0.2F, 0.0F, 0.2F, 1.1F, "trees everywhere");
        }
        if (is(biome, BiomeTags.IS_TAIGA)) {
            return new BiomeProfile("taiga", 0.9F, 0.5F, 0.25F, 0.0F, 0.2F, 1.1F, "spruce forest");
        }
        if (is(biome, BiomeTags.IS_SAVANNA)) {
            return new BiomeProfile("savanna", 0.5F, 0.7F, 0.2F, 0.1F, 0.1F, 1.0F, "scattered acacia");
        }
        if (is(biome, BiomeTags.IS_BEACH)) {
            return new BiomeProfile("beach", 0.05F, 0.2F, 0.1F, 1.0F, 0.8F, 1.0F, "sand and water, no trees");
        }
        if (is(biome, BiomeTags.IS_MOUNTAIN)) {
            return new BiomeProfile("mountain", 0.2F, 0.2F, 0.95F, 0.0F, 0.1F, 1.7F, "exposed stone, hard climbing");
        }
        if (is(biome, BiomeTags.IS_HILL)) {
            return new BiomeProfile("hills", 0.5F, 0.4F, 0.6F, 0.0F, 0.1F, 1.3F, "rolling stone and trees");
        }
        if (is(biome, BiomeTags.IS_NETHER)) {
            return new BiomeProfile("nether", 0.15F, 0.0F, 0.7F, 0.2F, 0.0F, 1.6F, "no overworld resources");
        }
        if (is(biome, BiomeTags.IS_END)) {
            return new BiomeProfile("the end", 0.0F, 0.0F, 0.1F, 0.0F, 0.0F, 1.4F, "nothing grows here");
        }
        return null;
    }

    /**
     * Last resort for a modded biome with no useful tags: guess from climate. A temperate biome
     * that rains is probably green; a hot dry one probably is not.
     */
    private static BiomeProfile fromClimate(Holder<Biome> biome) {
        if (!biome.isBound()) {
            return BiomeProfile.UNKNOWN;
        }
        Biome value = biome.value();
        float temperature = value.getBaseTemperature();
        boolean wet = value.hasPrecipitation();

        if (!wet && temperature >= 1.0F) {
            return new BiomeProfile("arid", 0.05F, 0.1F, 0.5F, 0.9F, 0.05F, 1.1F, "hot and dry, guessed");
        }
        if (wet && temperature >= 0.2F && temperature <= 1.0F) {
            return new BiomeProfile("temperate", 0.7F, 0.6F, 0.3F, 0.1F, 0.3F, 1.1F, "temperate and wet, guessed");
        }
        return BiomeProfile.UNKNOWN;
    }

    private static boolean is(Holder<Biome> biome, TagKey<Biome> tag) {
        return biome.is(tag);
    }

    private static void registerVanilla() {
        // Wood. What the bot heads for when it needs logs.
        put("forest", 0.95F, 0.6F, 0.2F, 0.0F, 0.2F, 1.0F, "oak and birch, easy walking");
        put("flower_forest", 0.8F, 0.8F, 0.2F, 0.0F, 0.2F, 1.0F, "trees plus flowers and bees");
        put("birch_forest", 0.95F, 0.5F, 0.2F, 0.0F, 0.2F, 1.0F, "tall birch");
        put("old_growth_birch_forest", 1.0F, 0.5F, 0.2F, 0.0F, 0.2F, 1.0F, "very tall birch");
        put("dark_forest", 1.0F, 0.5F, 0.2F, 0.0F, 0.2F, 1.2F, "dense dark oak, poor light");
        put("taiga", 0.95F, 0.5F, 0.3F, 0.0F, 0.2F, 1.0F, "spruce and sweet berries");
        put("old_growth_pine_taiga", 1.0F, 0.5F, 0.3F, 0.0F, 0.2F, 1.1F, "huge spruce");
        put("old_growth_spruce_taiga", 1.0F, 0.5F, 0.3F, 0.0F, 0.2F, 1.1F, "huge spruce");
        put("snowy_taiga", 0.9F, 0.4F, 0.3F, 0.0F, 0.2F, 1.1F, "spruce in snow");
        put("jungle", 1.0F, 0.7F, 0.2F, 0.0F, 0.3F, 1.4F, "dense wood, slow going");
        put("sparse_jungle", 0.7F, 0.6F, 0.2F, 0.0F, 0.3F, 1.2F, "thinner jungle");
        put("bamboo_jungle", 0.8F, 0.6F, 0.2F, 0.0F, 0.3F, 1.3F, "bamboo and jungle wood");
        put("cherry_grove", 0.9F, 0.6F, 0.3F, 0.0F, 0.1F, 1.2F, "cherry trees on hills");
        put("swamp", 0.8F, 0.4F, 0.1F, 0.1F, 0.8F, 1.4F, "oak over shallow water");
        put("mangrove_swamp", 0.85F, 0.3F, 0.1F, 0.1F, 0.9F, 1.5F, "mangrove over water");
        put("windswept_forest", 0.8F, 0.4F, 0.7F, 0.0F, 0.1F, 1.5F, "trees on broken stone");

        // Open land. Some wood, plenty of food, easy to cross.
        put("plains", 0.35F, 0.9F, 0.2F, 0.0F, 0.2F, 1.0F, "scattered oaks, lots of animals");
        put("sunflower_plains", 0.35F, 0.9F, 0.2F, 0.0F, 0.2F, 1.0F, "scattered oaks, lots of animals");
        put("meadow", 0.25F, 0.8F, 0.5F, 0.0F, 0.2F, 1.2F, "a few trees, open grass");
        put("savanna", 0.5F, 0.7F, 0.2F, 0.1F, 0.1F, 1.0F, "acacia and tall grass");
        put("savanna_plateau", 0.45F, 0.6F, 0.4F, 0.1F, 0.1F, 1.2F, "acacia on a plateau");
        put("windswept_savanna", 0.4F, 0.5F, 0.6F, 0.1F, 0.1F, 1.4F, "acacia on broken ground");
        put("mushroom_fields", 0.3F, 0.6F, 0.1F, 0.0F, 0.2F, 1.1F, "huge mushrooms, no normal trees");

        // Dead ends for wood. This is the "do not walk into the mesa for logs" knowledge.
        put("badlands", 0.0F, 0.05F, 0.85F, 0.8F, 0.05F, 1.3F, "no trees grow here, but gold is shallow");
        put("eroded_badlands", 0.0F, 0.05F, 0.9F, 0.8F, 0.05F, 1.6F, "no trees, hard to cross");
        put("wooded_badlands", 0.45F, 0.2F, 0.85F, 0.7F, 0.05F, 1.4F, "only the plateau tops have trees");
        put("desert", 0.0F, 0.05F, 0.3F, 1.0F, 0.05F, 1.0F, "no trees, sand and cactus");
        put("snowy_plains", 0.05F, 0.3F, 0.2F, 0.0F, 0.2F, 1.1F, "almost no trees");
        put("ice_spikes", 0.0F, 0.1F, 0.2F, 0.0F, 0.2F, 1.3F, "bare ice");
        put("frozen_peaks", 0.0F, 0.05F, 0.9F, 0.0F, 0.1F, 1.9F, "bare ice and stone");
        put("jagged_peaks", 0.0F, 0.05F, 0.95F, 0.0F, 0.1F, 1.9F, "bare stone, dangerous falls");
        put("stony_peaks", 0.0F, 0.05F, 1.0F, 0.0F, 0.1F, 1.8F, "bare stone");
        put("stony_shore", 0.0F, 0.1F, 1.0F, 0.1F, 0.6F, 1.4F, "bare stone at the waterline");
        put("snowy_slopes", 0.05F, 0.1F, 0.8F, 0.0F, 0.1F, 1.7F, "snow over stone");
        put("windswept_hills", 0.2F, 0.3F, 0.95F, 0.0F, 0.1F, 1.6F, "exposed stone and coal");
        put("windswept_gravelly_hills", 0.15F, 0.3F, 0.9F, 0.3F, 0.1F, 1.6F, "gravel and stone");

        // Water and shore. Expensive to cross, which is what keeps the bot out of the sea.
        put("beach", 0.05F, 0.2F, 0.1F, 1.0F, 0.8F, 1.0F, "sand and water, no trees");
        put("snowy_beach", 0.05F, 0.2F, 0.1F, 0.9F, 0.8F, 1.0F, "sand and ice, no trees");
        put("river", 0.2F, 0.3F, 0.1F, 0.4F, 1.0F, 1.5F, "crossable water, trees on the banks");
        put("frozen_river", 0.15F, 0.2F, 0.1F, 0.4F, 1.0F, 1.2F, "walkable ice");
        put("ocean", 0.0F, 0.15F, 0.0F, 0.2F, 1.0F, 3.0F, "open water, a long swim");
        put("deep_ocean", 0.0F, 0.1F, 0.0F, 0.1F, 1.0F, 3.5F, "deep open water");
        put("warm_ocean", 0.0F, 0.2F, 0.0F, 0.3F, 1.0F, 3.0F, "open water over reef");
        put("lukewarm_ocean", 0.0F, 0.15F, 0.0F, 0.2F, 1.0F, 3.0F, "open water");
        put("deep_lukewarm_ocean", 0.0F, 0.1F, 0.0F, 0.1F, 1.0F, 3.5F, "deep open water");
        put("cold_ocean", 0.0F, 0.15F, 0.0F, 0.2F, 1.0F, 3.0F, "cold open water");
        put("deep_cold_ocean", 0.0F, 0.1F, 0.0F, 0.1F, 1.0F, 3.5F, "deep cold water");
        put("frozen_ocean", 0.0F, 0.1F, 0.0F, 0.1F, 0.9F, 2.5F, "ice over water");
        put("deep_frozen_ocean", 0.0F, 0.05F, 0.0F, 0.1F, 0.9F, 3.0F, "ice over deep water");
    }

    private static void put(String path, float wood, float food, float stone, float sand, float water,
                            float travelCost, String notes) {
        VANILLA.put("minecraft:" + path,
                new BiomeProfile(path.replace('_', ' '), wood, food, stone, sand, water, travelCost, notes));
    }
}
