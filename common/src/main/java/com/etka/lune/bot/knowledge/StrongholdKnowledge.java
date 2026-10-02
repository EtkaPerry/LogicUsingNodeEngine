package com.etka.lune.bot.knowledge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * What a player who has read how the game lays out strongholds knows about them, and nothing a
 * player could not know.
 *
 * <p>None of this is read out of the world. The numbers are vanilla's {@code strongholds}
 * structure set - concentric rings of 3, 6, 10, 15, 21, 28, 36 and 9 around the world origin -
 * and the layout of the piece every stronghold starts with. They are the same numbers a speedrunner
 * carries in their head, and they are what turns one Eye of Ender into a place rather than a
 * direction. A world whose datapack moves the rings is still searched, only more slowly: nothing
 * fits, and {@link com.etka.lune.bot.task.EnderEyeTask} goes back to following the eye.</p>
 */
public final class StrongholdKnowledge {

    /**
     * From the chunk corner an eye of ender flies to, to the middle of the spiral staircase the
     * stronghold starts with.
     *
     * <p>The start piece is a 5 by 5 staircase placed two blocks in from that corner, and it is 5
     * by 5 whichever way it faces, so its middle column is four in on both axes. Digging down there
     * lands in the stairwell; digging down at the corner itself lands beside it.</p>
     */
    public static final int STAIRCASE_OFFSET = 4;

    /** Vanilla builds no stronghold piece further than this from its start, on either axis. */
    public static final int REACH = 112;

    /** The unit the ring distances are written in, in chunks. */
    private static final int DISTANCE = 32;
    private static final int COUNT = 128;
    private static final int FIRST_RING_SIZE = 3;
    /**
     * How far a stronghold may sit from the ring it was dealt, in blocks of radius.
     *
     * <p>Each one is moved to the nearest preferred biome within 112 blocks on either axis, and
     * then snapped to the corner of its chunk: at worst 136 blocks per axis, which is under 200
     * along any diagonal.</p>
     */
    private static final double SPREAD = 200.0;

    private static final int[] RING_SIZES = ringSizes();

    private StrongholdKnowledge() {}

    /**
     * The blocks, held apart so the ring arithmetic above never needs a registry: the tests that
     * check it have no game to boot.
     */
    private static final class Materials {
        /** Walls, floors and ceilings: the stone the whole structure is cut from. */
        static final Set<Block> MASONRY = Set.of(
                Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
                Blocks.CHISELED_STONE_BRICKS, Blocks.INFESTED_STONE_BRICKS,
                Blocks.INFESTED_MOSSY_STONE_BRICKS, Blocks.INFESTED_CRACKED_STONE_BRICKS,
                Blocks.INFESTED_CHISELED_STONE_BRICKS);

        /** What else a stronghold floor is made of: its slabs, its stairs and its cobbled rooms. */
        static final Set<Block> FLOORING = Set.of(
                Blocks.SMOOTH_STONE_SLAB, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_STAIRS,
                Blocks.COBBLESTONE, Blocks.COBBLESTONE_STAIRS);

        /** Ways through a wall that a sight ray stops at but a person walks through. */
        static final Set<Block> PASSAGES = Set.of(
                Blocks.OAK_DOOR, Blocks.IRON_DOOR, Blocks.IRON_BARS, Blocks.COBWEB);

        private Materials() {}
    }

    /** How many strongholds each ring holds, innermost first, worked out the way the game does. */
    static int[] ringSizes() {
        int[] sizes = new int[16];
        int rings = 0;
        int spread = FIRST_RING_SIZE;
        int inRing = 0;
        for (int placed = 0; placed < COUNT; placed++) {
            inRing++;
            if (inRing == spread || placed == COUNT - 1) {
                sizes[rings++] = inRing;
                inRing = 0;
                spread += 2 * spread / (rings + 1);
                spread = Math.min(spread, COUNT - placed);
            }
        }
        return java.util.Arrays.copyOf(sizes, rings);
    }

    /** Nearest a stronghold of ring {@code ring} can be to the origin, in blocks. */
    static double innerRadius(int ring) {
        return (4 * DISTANCE + DISTANCE * ring * 6 - DISTANCE * 1.25) * 16.0 - SPREAD;
    }

    /** Furthest a stronghold of ring {@code ring} can be from the origin, in blocks. */
    static double outerRadius(int ring) {
        return (4 * DISTANCE + DISTANCE * ring * 6 + DISTANCE * 1.25) * 16.0 + SPREAD;
    }

    /** Whether a chunk corner is somewhere any ring could have put a stronghold. */
    public static boolean onARing(double x, double z) {
        double radius = Math.sqrt(x * x + z * z);
        for (int ring = 0; ring < RING_SIZES.length; ring++) {
            if (radius >= innerRadius(ring) && radius <= outerRadius(ring)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The furthest the nearest stronghold can possibly be from {@code (x, z)}.
     *
     * <p>A ring's strongholds are evenly spaced round it, so wherever the player stands one of them
     * is within half a gap of their bearing from the origin. The eye flies to the nearest
     * stronghold there is, so anything further along its line than this bound is not it - however
     * well it happens to sit on the line.</p>
     */
    public static double nearestAtMost(double x, double z) {
        double from = Math.sqrt(x * x + z * z);
        double best = Double.POSITIVE_INFINITY;
        for (int ring = 0; ring < RING_SIZES.length; ring++) {
            double inner = innerRadius(ring);
            double outer = outerRadius(ring);
            double gap = Math.min(Math.PI, Math.PI / RING_SIZES[ring] + SPREAD / Math.max(1.0, inner));
            double worst = Math.max(chord(from, inner, gap), chord(from, outer, gap));
            best = Math.min(best, worst);
        }
        return best;
    }

    private static double chord(double a, double b, double angle) {
        return Math.sqrt(Math.max(0.0, a * a + b * b - 2.0 * a * b * Math.cos(angle)));
    }

    /** Stone bricks in any of their states, infested ones included. */
    public static boolean isMasonry(BlockState state) {
        return Materials.MASONRY.contains(state.getBlock());
    }

    /** A block a stronghold corridor or room could be standing on. */
    public static boolean isFloor(BlockState state) {
        return Materials.MASONRY.contains(state.getBlock())
                || Materials.FLOORING.contains(state.getBlock());
    }

    /** A door, a grate or a web: in the way of the eyes, not of the feet. */
    public static boolean isPassage(BlockState state) {
        return Materials.PASSAGES.contains(state.getBlock());
    }

    /** A block only the portal room has: its frame, its portal once lit, and its spawner. */
    public static boolean isPortalRoomSign(BlockState state) {
        return state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.END_PORTAL)
                || state.is(Blocks.SPAWNER);
    }

    /** The frame itself, which is what the portal room is found by. */
    public static boolean isPortal(BlockState state) {
        return state.is(Blocks.END_PORTAL_FRAME) || state.is(Blocks.END_PORTAL);
    }
}
