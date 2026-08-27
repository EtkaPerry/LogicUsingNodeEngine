package com.etka.lune.bot.knowledge;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.bot.util.Vision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Optional;
import java.util.Set;

/**
 * Reads the land around the bot and picks which way to go looking, the way a player does by
 * glancing at the horizon: head for the forest, not into the mesa, and do not swim an ocean when
 * there is dry land behind you.
 * <p>
 * It only samples chunks the client actually has, so this is not an X-ray - it is the same
 * information a player gets from standing there and looking, and it honours the project's rule that
 * the bot must not know things it could not see.
 */
public final class BiomeScout {

    /** Headings considered, every 22.5°. Finer than the four cardinals a player would never limit themselves to. */
    private static final int HEADINGS = 16;
    /** How far along a heading to sample, in blocks. Capped by render distance at runtime. */
    private static final int[] SAMPLE_DISTANCES = {24, 48, 80, 120, 160};
    /** Near samples matter more: they are both more certain and cheaper to reach. */
    private static final float[] SAMPLE_WEIGHTS = {1.0F, 0.85F, 0.65F, 0.45F, 0.3F};
    /** Favour carrying on the way we were already going instead of dithering between two equals. */
    private static final float MOMENTUM_BONUS = 1.18F;
    /** Turning round and walking back over ground already searched is the last thing to try. */
    private static final float BACKTRACK_PENALTY = 0.5F;
    /** Inside this angle counts as "the same way". */
    private static final float SAME_WAY_DEGREES = 50.0F;

    private static final String[] COMPASS = {
            "south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"
    };

    private BiomeScout() {}

    /**
     * A direction worth walking.
     *
     * @param yaw    Minecraft yaw to travel along
     * @param score  0 to 1-ish; higher is more promising
     * @param reason player-readable justification, e.g. "forest to the north-east"
     */
    public record Heading(float yaw, float score, String reason) {}

    /**
     * Picks the most promising heading for a set of block targets.
     *
     * @param momentumYaw the heading currently being followed, or null when starting fresh
     * @param cameFromYaw the heading the bot arrived along, or null; its reverse is penalised
     * @return empty when the bot has no opinion - nothing loaded, or targets it has no biome
     *         knowledge about - in which case the caller should fall back to its own search pattern
     */
    public static Optional<Heading> choose(BotContext ctx, Set<Block> targets,
                                           Float momentumYaw, Float cameFromYaw) {
        Set<Need> needs = Need.of(targets);
        if (needs.contains(Need.ANY)) {
            // No biome opinion applies; let the caller sweep however it likes.
            return Optional.empty();
        }

        BlockPos origin = ctx.player.blockPosition();
        int maxRange = (int) Math.min(Vision.maxRange(ctx), SAMPLE_DISTANCES[SAMPLE_DISTANCES.length - 1]);

        Heading best = null;
        for (int i = 0; i < HEADINGS; i++) {
            float yaw = Mth.wrapDegrees(i * (360.0F / HEADINGS));
            Heading candidate = rate(ctx, origin, yaw, needs, maxRange, momentumYaw, cameFromYaw);
            if (candidate != null && (best == null || candidate.score() > best.score())) {
                best = candidate;
            }
        }

        return Optional.ofNullable(best);
    }

    /** Walks one ray outward and turns what it finds into a score. */
    private static Heading rate(BotContext ctx, BlockPos origin, float yaw, Set<Need> needs,
                                int maxRange, Float momentumYaw, Float cameFromYaw) {
        double dx = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        double dz = Mth.cos(yaw * Mth.DEG_TO_RAD);

        float weighted = 0.0F;
        float travel = 0.0F;
        float totalWeight = 0.0F;
        float bestSample = -1.0F;
        String bestBiome = "";
        String bestNote = "";

        for (int i = 0; i < SAMPLE_DISTANCES.length; i++) {
            int distance = SAMPLE_DISTANCES[i];
            if (distance > maxRange) {
                break;
            }
            int x = origin.getX() + (int) Math.round(dx * distance);
            int z = origin.getZ() + (int) Math.round(dz * distance);
            if (!ctx.level.hasChunkAt(x, z)) {
                // Not loaded means not seen. Stop rather than guess about the far side.
                break;
            }

            BlockPos sample = groundAt(ctx.level, x, z, origin.getY());
            Holder<Biome> biome = ctx.level.getBiome(sample);
            BiomeProfile profile = BiomeKnowledge.profile(biome);

            float weight = SAMPLE_WEIGHTS[i];
            float availability = BiomeKnowledge.score(biome, needs);

            // The scout chooses destinations for ordinary exploration, whose GotoTask is
            // deliberately no-swim. A biome's resource rating is not enough to make a water
            // column a usable waypoint: a river bank may have trees, but the river itself is a
            // traversal barrier. Keep the farther samples useful (a forest beyond the river can
            // still win), while refusing to reward the water column the ray currently lands on.
            boolean walkable = MovementHelper.canStandAt(ctx.level, sample, false);
            if (!walkable) {
                availability = 0.0F;
                travel += 4.0F * weight;
            }

            weighted += availability * weight;
            if (walkable) {
                travel += profile.travelCost() * weight;
            }
            totalWeight += weight;

            if (availability > bestSample) {
                bestSample = availability;
                bestBiome = BiomeKnowledge.name(biome);
                bestNote = profile.notes();
            }
        }

        if (totalWeight <= 0.0F) {
            return null;
        }

        float availability = weighted / totalWeight;
        float cost = Math.max(1.0F, travel / totalWeight);
        // Dividing by travel cost is what stops the bot swimming an ocean to reach a tree it can
        // see on the far shore when there is a worse-but-walkable option behind it.
        float score = availability / cost;

        if (momentumYaw != null && isSameWay(yaw, momentumYaw)) {
            score *= MOMENTUM_BONUS;
        }
        if (cameFromYaw != null && isSameWay(yaw, Mth.wrapDegrees(cameFromYaw + 180.0F))) {
            score *= BACKTRACK_PENALTY;
        }

        String reason = bestSample <= 0.1F
                ? "nothing but " + bestBiome + " to the " + compass(yaw) + " - " + bestNote
                : bestBiome + " to the " + compass(yaw);
        return new Heading(yaw, score, reason);
    }

    /**
     * The walkable surface at an XZ column. Exploration goals have to sit on the ground: a goal left
     * at the bot's own Y ends up buried in a hillside or hanging over water, and the pathfinder can
     * only answer that with an empty path.
     */
    public static BlockPos groundAt(Level level, int x, int z, int fallbackY) {
        if (!level.hasChunkAt(x, z)) {
            return new BlockPos(x, fallbackY, z);
        }
        int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int clamped = Mth.clamp(surface, level.getMinY() + 1, level.getMaxY() - 1);
        return new BlockPos(x, clamped, z);
    }

    /** Compass word for a yaw, for status lines the player can actually read. */
    public static String compass(float yaw) {
        return COMPASS[Math.floorMod(Math.round(Mth.wrapDegrees(yaw) / 45.0F), 8)];
    }

    private static boolean isSameWay(float a, float b) {
        return Math.abs(Mth.wrapDegrees(a - b)) <= SAME_WAY_DEGREES;
    }
}
