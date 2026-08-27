package com.etka.lune.bot.task;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.Set;

/**
 * The things a speedrunner diverts for when they are walking across the Overworld looking for iron.
 *
 * <p>A player does not only look for exposed ore. They look for the shapes that mean <em>someone
 * else already did the mining</em> - a shipwreck, a village smithy, a mineshaft opening in a ravine,
 * a ruined portal. Each of those is worth a short detour and none of them are visible to a search
 * that only knows the block ID of iron ore.</p>
 *
 * <p>Every marker here is chosen because it is close to unique in the Overworld, so a sighting is
 * evidence of the structure rather than a guess:</p>
 * <ul>
 *   <li>{@code NETHERRACK} and {@code CRYING_OBSIDIAN} generate in the Overworld only as part of a
 *       ruined portal.</li>
 *   <li>{@code BLAST_FURNACE} generates only in a village smith's house.</li>
 *   <li>Rails generate only in mineshafts.</li>
 *   <li>Chests and barrels are the payload of every one of these structures, and of shipwrecks,
 *       which have no block of their own that a tree does not also have.</li>
 * </ul>
 */
public enum SpeedrunOpportunity {

    /**
     * A ruined portal. Its own obsidian needs a diamond pickaxe, which a run at this stage does not
     * have, so the value is the chest: flint and steel or a fire charge skips the whole flint phase,
     * and any obsidian in it counts toward building a frame outright.
     */
    RUINED_PORTAL("ruined portal"),
    /** A village smithy. The most reliable iron in the Overworld that nobody has to mine. */
    BLACKSMITH("village smithy"),
    /**
     * A pillager outpost. Its chest is on the top floor, so it is never visible from the ground -
     * which is why a bot looking only for chests circles the tower instead of going up it. The
     * banner on the roof is what a player actually recognises the place by, from a long way off.
     */
    PILLAGER_OUTPOST("pillager outpost"),
    /** A chest or barrel in the open: shipwreck, village, temple, or a ruined portal's own cache. */
    CONTAINER("supply chest"),
    /** A mineshaft opening. Exposed iron in the walls, plus chests, without sinking a shaft. */
    MINESHAFT("mineshaft");

    private final String label;

    SpeedrunOpportunity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Blocks whose sighting means this opportunity is in view. */
    public Set<Block> markers() {
        return switch (this) {
            case RUINED_PORTAL -> Set.of(Blocks.NETHERRACK, Blocks.CRYING_OBSIDIAN);
            case BLACKSMITH -> Set.of(Blocks.BLAST_FURNACE);
            case PILLAGER_OUTPOST -> Set.of(Blocks.WHITE_BANNER, Blocks.WHITE_WALL_BANNER);
            case CONTAINER -> Set.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
            case MINESHAFT -> Set.of(Blocks.RAIL, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL,
                    Blocks.ACTIVATOR_RAIL);
        };
    }

    /** Every marker block, for handing to a single search alongside the ore targets. */
    public static Set<Block> allMarkers() {
        Set<Block> all = new HashSet<>();
        for (SpeedrunOpportunity opportunity : values()) {
            all.addAll(opportunity.markers());
        }
        return Set.copyOf(all);
    }

    /** Which opportunity a sighted block represents, or null when it is not a marker at all. */
    public static SpeedrunOpportunity classify(Block block) {
        for (SpeedrunOpportunity opportunity : values()) {
            if (opportunity.markers().contains(block)) {
                return opportunity;
            }
        }
        return null;
    }
}
