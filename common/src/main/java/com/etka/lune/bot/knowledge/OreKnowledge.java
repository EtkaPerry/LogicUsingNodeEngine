package com.etka.lune.bot.knowledge;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Static ore-layer knowledge the bot uses to decide where to dig instead of staircase-prospecting
 * blindly until it hits bedrock.
 * <p>
 * The data is vanilla 1.21 by default. {@link com.etka.lune.config.BotConfig#oreProfiles} can
 * override or add to it at runtime.
 */
public final class OreKnowledge {

    private static final Map<Block, OreProfile> PROFILES = new HashMap<>();
    private static boolean configLoaded = false;

    private OreKnowledge() {}

    static {
        registerVanilla();
    }

    /** Looks up the profile for a single block, if any. */
    public static Optional<OreProfile> forBlock(Block block) {
        ensureConfigLoaded();
        return Optional.ofNullable(PROFILES.get(block));
    }

    /** Returns the best Y level for a set of target blocks, taking current dimension. */
    public static OptionalInt bestYFor(BotContext ctx, Set<Block> targets) {
        if (targets == null || targets.isEmpty()) {
            return OptionalInt.empty();
        }

        Level level = ctx.level;
        BlockPos pos = ctx.player.blockPosition();
        String dimKey = dimensionId(level);

        int bestY = Integer.MIN_VALUE;
        boolean any = false;

        for (Block block : targets) {
            for (OreProfile profile : uniqueProfiles(block)) {
                if (profile.dimension().isPresent() && !dimKey.equals(profile.dimension().get())) {
                    continue;
                }
                if (profile.biome().isPresent() && !biomeId(level, pos).equals(profile.biome().get())) {
                    continue;
                }
                if (!any || closestTo(bestY, profile.bestY(), pos.getY()) == profile.bestY()) {
                    bestY = profile.bestY();
                    any = true;
                }
            }
        }

        return any ? OptionalInt.of(bestY) : OptionalInt.empty();
    }

    /**
     * The Y a prospect should be aiming at, for targets that may have no depth knowledge at all.
     *
     * <p>Falls back to the level the player is already standing on, and that fallback is the whole
     * point of the method. Callers used to fall back to their own search floor, which is a filter -
     * "ignore anything below this height", defaulting to the bottom of the world - and reading a
     * filter as a destination turns any request for a block this table has never heard of into a
     * staircase to bedrock. That is not a hypothetical: a job collecting plain deepslate from a
     * mountain top walked away from the deposit and dug two hundred blocks down, because deepslate
     * itself is not an ore and has no profile here.
     *
     * <p>Staying level is the honest answer to "where is this?" when nothing is known: the prospect
     * then branches horizontally to expose what is around, which is what a player does with an
     * unfamiliar block, and the caller's own y-bounds still exclude anything out of range.
     */
    public static int prospectYFor(BotContext ctx, Set<Block> targets) {
        return bestYFor(ctx, targets).orElse(ctx.player.blockPosition().getY());
    }

    /** Returns the profile's vertical band for a set of target blocks, or a wide fallback. */
    public static int[] yRangeFor(BotContext ctx, Set<Block> targets) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        boolean any = false;

        Level level = ctx.level;
        BlockPos pos = ctx.player.blockPosition();
        String dimKey = dimensionId(level);

        for (Block block : targets) {
            for (OreProfile profile : uniqueProfiles(block)) {
                if (profile.dimension().isPresent() && !dimKey.equals(profile.dimension().get())) {
                    continue;
                }
                if (profile.biome().isPresent() && !biomeId(level, pos).equals(profile.biome().get())) {
                    continue;
                }
                min = Math.min(min, profile.yMin());
                max = Math.max(max, profile.yMax());
                any = true;
            }
        }

        return any ? new int[]{min, max} : new int[]{level.getMinY(), level.getMaxY()};
    }

    /**
     * The world's id as a profile writes it: {@code minecraft:the_nether}.
     *
     * <p>A {@link net.minecraft.resources.ResourceKey} prints itself as
     * {@code ResourceKey[minecraft:dimension / minecraft:the_nether]}, which equals no id anybody
     * would write in a profile or in {@code lune.json}. Both filters here compared that sentence
     * against a plain id, so the one built-in profile that names a world - Ancient Debris, in the
     * Nether - was skipped in the Nether as well as out of it: a stripmine asked for debris fell
     * back to the card's own y-level, which defaults to -59 and is below the Nether's bedrock.
     * Every other dimension comparison in the codebase already asks for the identifier.</p>
     */
    private static String dimensionId(Level level) {
        return level.dimension().identifier().toString();
    }

    /** The same for a biome, which a profile also names by id. */
    private static String biomeId(Level level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("");
    }

    /** Returns true if any of the targets is known to generate better in open caves. */
    public static boolean cavePreferred(Set<Block> targets) {
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        for (Block block : targets) {
            for (OreProfile profile : uniqueProfiles(block)) {
                if (profile.cavePreferred()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Applies config overrides. Call once after BotConfig is loaded. */
    public static synchronized void loadConfig(Map<String, com.etka.lune.config.OreProfileData> config) {
        configLoaded = true;
        if (config == null || config.isEmpty()) {
            return;
        }
        for (Map.Entry<String, com.etka.lune.config.OreProfileData> entry : config.entrySet()) {
            com.etka.lune.config.OreProfileData data = entry.getValue();
            Set<Block> blocks = new HashSet<>();
            for (String id : data.blocks) {
                Identifier loc = Identifier.tryParse(id);
                if (loc != null) {
                    blocks.add(BuiltInRegistries.BLOCK.getOptional(loc).orElse(null));
                }
            }
            blocks.remove(null);
            if (blocks.isEmpty()) {
                continue;
            }
            OreProfile profile = new OreProfile(
                    entry.getKey(), blocks, data.bestY, data.yMin, data.yMax,
                    Optional.ofNullable(data.biome),
                    Optional.ofNullable(data.dimension),
                    data.cavePreferred,
                    data.notes == null ? "" : data.notes);
            for (Block b : blocks) {
                PROFILES.put(b, profile);
            }
        }
    }

    private static void ensureConfigLoaded() {
        if (!configLoaded) {
            configLoaded = true;
            loadConfig(com.etka.lune.config.BotConfig.get().oreProfiles);
        }
    }

    /** All profiles associated with a block, plus the same profile shared by its ore variants. */
    private static Set<OreProfile> uniqueProfiles(Block block) {
        ensureConfigLoaded();
        OreProfile profile = PROFILES.get(block);
        if (profile != null) {
            return Set.of(profile);
        }
        return Collections.emptySet();
    }

    private static int closestTo(int a, int b, int y) {
        return Math.abs(a - y) <= Math.abs(b - y) ? a : b;
    }

    private static void register(Block... blocks) {
        // Helper to register one profile for several block variants.
    }

    private static void registerVanilla() {
        // Diamond / redstone - peak right above bedrock.
        put("Diamond", Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), -59, -64, 16,
                Optional.empty(), Optional.empty(), false,
                "lune.ore.concentration_rises_towards_bedrock");

        put("Redstone", Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE), -59, -64, 16,
                Optional.empty(), Optional.empty(), false,
                "lune.ore.same_distribution_curve_as_diamond");

        // Iron - two bands: underground and exposed mountain tops.
        put("Iron", Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 16, -64, 64,
                Optional.empty(), Optional.empty(), false,
                "lune.ore.best_underground_strip_mining_level_y_16");

        // Gold - underground peak; badlands are handled via a separate high-altitude profile.
        put("Gold", Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE), -16, -64, 32,
                Optional.empty(), Optional.empty(), false,
                "lune.ore.peak_concentration_underground");

        // Lapis - strictly strip-mined, best not exposed.
        put("Lapis", Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE), 0, -64, 64,
                Optional.empty(), Optional.empty(), false,
                "lune.ore.generates_best_when_exposed_air");

        // Copper - best at Y=48 and larger in Dripstone Caves.
        put("Copper", Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE), 48, -16, 112,
                Optional.empty(), Optional.empty(), false,
                "lune.ore.y_48_main_band_veins_larger_dripstone");

        // Coal - peaks in hills.
        put("Coal", Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE), 96, 0, 136,
                Optional.empty(), Optional.empty(), false,
                "lune.ore.does_spawn_below_y_0_best_hills");

        // Emerald - only mountain/windswept biomes.
        put("Emerald", Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE), 232, 200, 320,
                Optional.empty(), Optional.empty(), false,
                "lune.ore.only_found_mountain_or_windswept_hills");

        // Ancient Debris / Netherite - only the Nether.
        put("Netherite", Set.of(Blocks.ANCIENT_DEBRIS), 15, 8, 119,
                Optional.empty(), Optional.of("minecraft:the_nether"), false,
                "lune.ore.found_only_nether_beds_or_tnt_help");
    }

    private static void put(String name, Set<Block> blocks, int bestY, int yMin, int yMax,
                            Optional<String> biome, Optional<String> dimension,
                            boolean cavePreferred, String notes) {
        OreProfile profile = new OreProfile(name, blocks, bestY, yMin, yMax,
                biome, dimension, cavePreferred, notes);
        for (Block block : blocks) {
            PROFILES.put(block, profile);
        }
    }
}
