package com.etka.lune.bot.knowledge;

import com.etka.lune.util.Lang;
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

    /** English on purpose: this is what the run journal records, and it stays greppable. */
    public static String name(Holder<Biome> biome) {
        if (biome == null) {
            return "unknown";
        }
        return biome.unwrapKey()
                .map(key -> key.identifier().getPath().replace('_', ' '))
                .orElse("unknown");
    }

    /**
     * What the player calls this biome, for status lines and anything Lune says.
     *
     * <p>Minecraft already ships the name in every language it supports, so this asks the game
     * rather than keeping a second list that would go stale the next time a biome is added. A
     * biome with no key falls back to {@link #name}, which is the id with its underscores taken
     * out - readable, if not translated.</p>
     */
    public static String displayName(Holder<Biome> biome) {
        if (biome == null) {
            return Lang.get("lune.biome.unknown");
        }
        return biome.unwrapKey()
                .map(key -> "biome." + key.identifier().getNamespace() + "."
                        + key.identifier().getPath())
                .filter(Lang::has)
                .map(Lang::get)
                .orElseGet(() -> name(biome));
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
            return new BiomeProfile(Lang.get("lune.biome.deep_ocean"), 0.0F, 0.1F, 0.0F, 0.1F, 1.0F, 3.5F, "lune.biome.open_water_nothing_grows");
        }
        if (is(biome, BiomeTags.IS_OCEAN)) {
            return new BiomeProfile("ocean", 0.0F, 0.15F, 0.0F, 0.2F, 1.0F, 3.0F, "lune.biome.open_water_nothing_grows");
        }
        if (is(biome, BiomeTags.IS_RIVER)) {
            return new BiomeProfile("river", 0.2F, 0.3F, 0.1F, 0.4F, 1.0F, 1.5F, "lune.biome.water_banks_may_have_trees");
        }
        if (is(biome, BiomeTags.IS_BADLANDS)) {
            return new BiomeProfile("badlands", 0.0F, 0.05F, 0.85F, 0.8F, 0.05F, 1.3F, "lune.biome.trees_grow_here");
        }
        if (is(biome, BiomeTags.IS_JUNGLE)) {
            return new BiomeProfile("jungle", 1.0F, 0.7F, 0.2F, 0.0F, 0.3F, 1.4F, "lune.biome.dense_wood_slow_going");
        }
        if (is(biome, BiomeTags.IS_FOREST)) {
            return new BiomeProfile("forest", 0.95F, 0.6F, 0.2F, 0.0F, 0.2F, 1.1F, "lune.biome.trees_everywhere");
        }
        if (is(biome, BiomeTags.IS_TAIGA)) {
            return new BiomeProfile("taiga", 0.9F, 0.5F, 0.25F, 0.0F, 0.2F, 1.1F, "lune.biome.spruce_forest");
        }
        if (is(biome, BiomeTags.IS_SAVANNA)) {
            return new BiomeProfile("savanna", 0.5F, 0.7F, 0.2F, 0.1F, 0.1F, 1.0F, "lune.biome.scattered_acacia");
        }
        if (is(biome, BiomeTags.IS_BEACH)) {
            return new BiomeProfile("beach", 0.05F, 0.2F, 0.1F, 1.0F, 0.8F, 1.0F, "lune.biome.sand_water_trees");
        }
        if (is(biome, BiomeTags.IS_MOUNTAIN)) {
            return new BiomeProfile("mountain", 0.2F, 0.2F, 0.95F, 0.0F, 0.1F, 1.7F, "lune.biome.exposed_stone_hard_climbing");
        }
        if (is(biome, BiomeTags.IS_HILL)) {
            return new BiomeProfile("hills", 0.5F, 0.4F, 0.6F, 0.0F, 0.1F, 1.3F, "lune.biome.rolling_stone_trees");
        }
        if (is(biome, BiomeTags.IS_NETHER)) {
            return new BiomeProfile("nether", 0.15F, 0.0F, 0.7F, 0.2F, 0.0F, 1.6F, "lune.biome.overworld_resources");
        }
        if (is(biome, BiomeTags.IS_END)) {
            return new BiomeProfile(Lang.get("lune.biome.end"), 0.0F, 0.0F, 0.1F, 0.0F, 0.0F, 1.4F, "lune.biome.nothing_grows_here");
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
            return new BiomeProfile("arid", 0.05F, 0.1F, 0.5F, 0.9F, 0.05F, 1.1F, "lune.biome.hot_dry_guessed");
        }
        if (wet && temperature >= 0.2F && temperature <= 1.0F) {
            return new BiomeProfile("temperate", 0.7F, 0.6F, 0.3F, 0.1F, 0.3F, 1.1F, "lune.biome.temperate_wet_guessed");
        }
        return BiomeProfile.UNKNOWN;
    }

    private static boolean is(Holder<Biome> biome, TagKey<Biome> tag) {
        return biome.is(tag);
    }

    private static void registerVanilla() {
        // Wood. What the bot heads for when it needs logs.
        put("forest", 0.95F, 0.6F, 0.2F, 0.0F, 0.2F, 1.0F, "lune.biome.oak_birch_easy_walking");
        put("flower_forest", 0.8F, 0.8F, 0.2F, 0.0F, 0.2F, 1.0F, "lune.biome.trees_plus_flowers_bees");
        put("birch_forest", 0.95F, 0.5F, 0.2F, 0.0F, 0.2F, 1.0F, "lune.biome.tall_birch");
        put("old_growth_birch_forest", 1.0F, 0.5F, 0.2F, 0.0F, 0.2F, 1.0F, "lune.biome.very_tall_birch");
        put("dark_forest", 1.0F, 0.5F, 0.2F, 0.0F, 0.2F, 1.2F, "lune.biome.dense_dark_oak_poor_light");
        put("taiga", 0.95F, 0.5F, 0.3F, 0.0F, 0.2F, 1.0F, "lune.biome.spruce_sweet_berries");
        put("old_growth_pine_taiga", 1.0F, 0.5F, 0.3F, 0.0F, 0.2F, 1.1F, "lune.biome.huge_spruce");
        put("old_growth_spruce_taiga", 1.0F, 0.5F, 0.3F, 0.0F, 0.2F, 1.1F, "lune.biome.huge_spruce");
        put("snowy_taiga", 0.9F, 0.4F, 0.3F, 0.0F, 0.2F, 1.1F, "lune.biome.spruce_snow");
        put("jungle", 1.0F, 0.7F, 0.2F, 0.0F, 0.3F, 1.4F, "lune.biome.dense_wood_slow_going");
        put("sparse_jungle", 0.7F, 0.6F, 0.2F, 0.0F, 0.3F, 1.2F, "lune.biome.thinner_jungle");
        put("bamboo_jungle", 0.8F, 0.6F, 0.2F, 0.0F, 0.3F, 1.3F, "lune.biome.bamboo_jungle_wood");
        put("cherry_grove", 0.9F, 0.6F, 0.3F, 0.0F, 0.1F, 1.2F, "lune.biome.cherry_trees_hills");
        put("swamp", 0.8F, 0.4F, 0.1F, 0.1F, 0.8F, 1.4F, "lune.biome.oak_over_shallow_water");
        put("mangrove_swamp", 0.85F, 0.3F, 0.1F, 0.1F, 0.9F, 1.5F, "lune.biome.mangrove_over_water");
        put("windswept_forest", 0.8F, 0.4F, 0.7F, 0.0F, 0.1F, 1.5F, "lune.biome.trees_broken_stone");

        // Open land. Some wood, plenty of food, easy to cross.
        put("plains", 0.35F, 0.9F, 0.2F, 0.0F, 0.2F, 1.0F, "lune.biome.scattered_oaks_lots_animals");
        put("sunflower_plains", 0.35F, 0.9F, 0.2F, 0.0F, 0.2F, 1.0F, "lune.biome.scattered_oaks_lots_animals");
        put("meadow", 0.25F, 0.8F, 0.5F, 0.0F, 0.2F, 1.2F, "lune.biome.few_trees_open_grass");
        put("savanna", 0.5F, 0.7F, 0.2F, 0.1F, 0.1F, 1.0F, "lune.biome.acacia_tall_grass");
        put("savanna_plateau", 0.45F, 0.6F, 0.4F, 0.1F, 0.1F, 1.2F, "lune.biome.acacia_plateau");
        put("windswept_savanna", 0.4F, 0.5F, 0.6F, 0.1F, 0.1F, 1.4F, "lune.biome.acacia_broken_ground");
        put("mushroom_fields", 0.3F, 0.6F, 0.1F, 0.0F, 0.2F, 1.1F, "lune.biome.huge_mushrooms_normal_trees");

        // Dead ends for wood. This is the "do not walk into the mesa for logs" knowledge.
        put("badlands", 0.0F, 0.05F, 0.85F, 0.8F, 0.05F, 1.3F, "lune.biome.trees_grow_here_but_gold_shallow");
        put("eroded_badlands", 0.0F, 0.05F, 0.9F, 0.8F, 0.05F, 1.6F, "lune.biome.trees_hard_cross");
        put("wooded_badlands", 0.45F, 0.2F, 0.85F, 0.7F, 0.05F, 1.4F, "lune.biome.only_plateau_tops_have_trees");
        put("desert", 0.0F, 0.05F, 0.3F, 1.0F, 0.05F, 1.0F, "lune.biome.trees_sand_cactus");
        put("snowy_plains", 0.05F, 0.3F, 0.2F, 0.0F, 0.2F, 1.1F, "lune.biome.almost_trees");
        put("ice_spikes", 0.0F, 0.1F, 0.2F, 0.0F, 0.2F, 1.3F, "lune.biome.bare_ice");
        put("frozen_peaks", 0.0F, 0.05F, 0.9F, 0.0F, 0.1F, 1.9F, "lune.biome.bare_ice_stone");
        put("jagged_peaks", 0.0F, 0.05F, 0.95F, 0.0F, 0.1F, 1.9F, "lune.biome.bare_stone_dangerous_falls");
        put("stony_peaks", 0.0F, 0.05F, 1.0F, 0.0F, 0.1F, 1.8F, "lune.biome.bare_stone");
        put("stony_shore", 0.0F, 0.1F, 1.0F, 0.1F, 0.6F, 1.4F, "lune.biome.bare_stone_waterline");
        put("snowy_slopes", 0.05F, 0.1F, 0.8F, 0.0F, 0.1F, 1.7F, "lune.biome.snow_over_stone");
        put("windswept_hills", 0.2F, 0.3F, 0.95F, 0.0F, 0.1F, 1.6F, "lune.biome.exposed_stone_coal");
        put("windswept_gravelly_hills", 0.15F, 0.3F, 0.9F, 0.3F, 0.1F, 1.6F, "lune.biome.gravel_stone");

        // Water and shore. Expensive to cross, which is what keeps the bot out of the sea.
        put("beach", 0.05F, 0.2F, 0.1F, 1.0F, 0.8F, 1.0F, "lune.biome.sand_water_trees");
        put("snowy_beach", 0.05F, 0.2F, 0.1F, 0.9F, 0.8F, 1.0F, "lune.biome.sand_ice_trees");
        put("river", 0.2F, 0.3F, 0.1F, 0.4F, 1.0F, 1.5F, "lune.biome.crossable_water_trees_banks");
        put("frozen_river", 0.15F, 0.2F, 0.1F, 0.4F, 1.0F, 1.2F, "lune.biome.walkable_ice");
        put("ocean", 0.0F, 0.15F, 0.0F, 0.2F, 1.0F, 3.0F, "lune.biome.open_water_long_swim");
        put("deep_ocean", 0.0F, 0.1F, 0.0F, 0.1F, 1.0F, 3.5F, "lune.biome.deep_open_water");
        put("warm_ocean", 0.0F, 0.2F, 0.0F, 0.3F, 1.0F, 3.0F, "lune.biome.open_water_over_reef");
        put("lukewarm_ocean", 0.0F, 0.15F, 0.0F, 0.2F, 1.0F, 3.0F, "lune.biome.open_water");
        put("deep_lukewarm_ocean", 0.0F, 0.1F, 0.0F, 0.1F, 1.0F, 3.5F, "lune.biome.deep_open_water");
        put("cold_ocean", 0.0F, 0.15F, 0.0F, 0.2F, 1.0F, 3.0F, "lune.biome.cold_open_water");
        put("deep_cold_ocean", 0.0F, 0.1F, 0.0F, 0.1F, 1.0F, 3.5F, "lune.biome.deep_cold_water");
        put("frozen_ocean", 0.0F, 0.1F, 0.0F, 0.1F, 0.9F, 2.5F, "lune.biome.ice_over_water");
        put("deep_frozen_ocean", 0.0F, 0.05F, 0.0F, 0.1F, 0.9F, 3.0F, "lune.biome.ice_over_deep_water");
    }

    private static void put(String path, float wood, float food, float stone, float sand, float water,
                            float travelCost, String notes) {
        VANILLA.put("minecraft:" + path,
                new BiomeProfile(path.replace('_', ' '), wood, food, stone, sand, water, travelCost, notes));
    }
}
