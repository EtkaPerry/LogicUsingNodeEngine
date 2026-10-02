package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The world-reading predicates the pathfinder is built from: can the player occupy this block, can
 * they stand here, will this hurt, is it worth breaking.
 * <p>
 * Everything here is a pure function of the block snapshot, so the pathfinder stays testable and
 * cheap - no entity state, no side effects.
 */
public final class MovementHelper {

    /**
     * Vanilla's step-up height. Anything shorter than this can be walked over without jumping.
     */
    private static final double STEP_HEIGHT = 0.6;
    /**
     * Leaves are priced well above their hardness on purpose.
     * <p>
     * By hardness alone a leaf costs about the same as walking one block, which makes ploughing
     * straight through a canopy cheaper than walking round the tree. That is not what a player does
     * - they walk under a canopy and fell the trunk from the ground - and a bot that tunnels in
     * ends up inside the tree it was trying to chop, breaking leaves instead of wood. This is high
     * enough to make going round preferable, and low enough that clipping one or two leaves out of
     * the way is still allowed when they genuinely are the short route.
     */
    private static final double LEAF_BREAK_COST = 5.0;

    /** A block the player's body can occupy: nothing solid in the way, and nothing that hurts. */
    public static boolean isPassable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isHarmful(state)) {
            return false;
        }
        if (state.isAir()) {
            return true;
        }
        // A ladder is a route, not a wall. Its collision box is a thin plate against the wall it
        // hangs on and runs the full height of the block, so the height test below calls it solid -
        // which made every ladder in the world unpathable, and made a bot standing on one believe
        // its own body was trapped and start mining the ladder out from under itself.
        if (state.is(BlockTags.CLIMBABLE)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return true;
        }
        // Snow layers, carpets and pressure plates have a collision box but are low enough that the
        // player walks straight over them. Treating them as walls made the bot refuse to cross
        // ordinary snowy or carpeted ground.
        return shape.max(Direction.Axis.Y) <= STEP_HEIGHT;
    }

    /**
     * The pathfinding block occupied by a player's feet.
     * <p>
     * {@link Player#blockPosition()} floors the physical Y coordinate. On farmland, soul sand,
     * slabs, and other shorter floors that puts an on-ground player inside the supporting block,
     * one node below the walkable body space. Comparing routes against that raw value makes every
     * flat step across a farm look like a one-block climb and causes the executor to jump. The
     * support block is the reliable reference while grounded; in air and water the raw position is
     * still the honest one.
     */
    public static BlockPos feetPosition(Player player) {
        BlockPos raw = player.blockPosition();
        if (!player.onGround()) {
            return raw;
        }
        BlockPos supported = player.getOnPos().above();
        return canStandAt(player.level(), supported) ? supported.immutable() : raw;
    }

    /** A block whose top face the player can stand on. */
    public static boolean isSolidFloor(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isHarmful(state)) {
            return false;
        }
        // A full-height collision box is the reliable signal; slabs and stairs also qualify because
        // their collision shape still reaches the top of the block from above.
        return blocksMotion(state) && !state.getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Whether a feet position is a dependable place to begin a descending mining stair.
     * <p>
     * A full collision block is not enough: a crafting table placed on leaves is technically a
     * floor, but it is still a tree canopy. Starting there makes the first stair open into air and
     * leaves the miner with no safe way to expose the stone below. This predicate is shared by
     * early stone gathering and the general Mine/Find prospectors so they all recover the same way.
     */
    public static boolean isStableMiningStart(BlockGetter level, BlockPos feet) {
        if (!canStandAt(level, feet, false)) {
            return false;
        }
        BlockState body = level.getBlockState(feet);
        if (body.is(BlockTags.LEAVES) || body.is(BlockTags.LOGS)) {
            return false;
        }
        BlockPos supportPos = feet.below();
        BlockState support = level.getBlockState(supportPos);
        if (support.is(BlockTags.LEAVES) || support.is(BlockTags.LOGS)) {
            return false;
        }
        // Reject artificial platforms sitting directly on a canopy as well as the canopy itself.
        // A table on ordinary terrain remains valid because the block below it is real ground.
        BlockState underSupport = level.getBlockState(supportPos.below());
        return !underSupport.is(BlockTags.LEAVES)
                && !underSupport.is(BlockTags.LOGS)
                && !isWater(level, supportPos)
                && !isLava(level, supportPos)
                && !isWater(level, supportPos.below())
                && !isLava(level, supportPos.below());
    }

    /** Loose ground is usable, but a short early stair often spends its whole budget in it. */
    public static boolean isLooseMiningSurface(BlockGetter level, BlockPos feet) {
        BlockState support = level.getBlockState(feet.below());
        return support.is(Blocks.SAND) || support.is(Blocks.RED_SAND)
                || support.is(Blocks.GRAVEL);
    }

    /**
     * Finds the nearest loaded, stable mining start around the player. The search only reads
     * already-loaded chunks; it never loads terrain or selects a hidden resource block.
     *
     * @param avoidLoose when true, prefer solid ground but retain a loose-ground fallback
     */
    public static BlockPos findStableMiningStart(Level level, BlockPos current, int horizontalRadius,
                                                  int minDy, int maxDy, Set<Long> excluded,
                                                  boolean avoidLoose) {
        BlockPos best = null;
        BlockPos looseFallback = null;
        BlockPos verticalFallback = null;
        int bestDistance = Integer.MAX_VALUE;
        int looseDistance = Integer.MAX_VALUE;
        int verticalDistance = Integer.MAX_VALUE;
        int radiusLimit = Math.max(0, horizontalRadius);
        int lower = Math.min(minDy, maxDy);
        int upper = Math.max(minDy, maxDy);

        for (int radius = 0; radius <= radiusLimit; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = upper; dy >= lower; dy--) {
                        BlockPos candidate = current.offset(dx, dy, dz);
                        if (!level.hasChunkAt(candidate)
                                || (excluded != null && excluded.contains(candidate.asLong()))
                                || !isStableMiningStart(level, candidate)) {
                            continue;
                        }
                        int distance = Math.abs(dx) + Math.abs(dz) + Math.abs(dy);
                        // A floor directly below the caller is often the bottom of the same hole
                        // or canopy lip the caller is trapped above. Remember it as a last resort,
                        // but do not return it before checking nearby horizontal ground: GotoTask
                        // can route to a lower column, yet it cannot make a player walk straight
                        // down into a final waypoint when there is no lateral foothold. This also
                        // covers the one-block descent that used to reset the stone retry forever.
                        if (dx == 0 && dz == 0 && dy < 0) {
                            if (distance < verticalDistance) {
                                verticalFallback = candidate.immutable();
                                verticalDistance = distance;
                            }
                            continue;
                        }
                        if (avoidLoose && isLooseMiningSurface(level, candidate)) {
                            if (distance < looseDistance) {
                                looseFallback = candidate.immutable();
                                looseDistance = distance;
                            }
                            continue;
                        }
                        if (distance < bestDistance) {
                            best = candidate;
                            bestDistance = distance;
                        }
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }
        if (looseFallback != null) {
            return looseFallback;
        }
        return verticalFallback;
    }

    /**
     * Whether the player can stand with their feet at {@code feet}: two blocks of clearance and a
     * floor underneath, or a water surface the player can float on.
     */
    public static boolean canStandAt(BlockGetter level, BlockPos feet) {
        return canStandAt(level, feet, true);
    }

    /**
     * Whether the player can occupy a feet block when the caller controls whether swimming is
     * allowed. Ordinary ground travel must not treat the top of a river as a normal floor: doing
     * so makes a no-swim route step onto the water surface and then sink when physics catches up.
     */
    public static boolean canStandAt(BlockGetter level, BlockPos feet, boolean allowSwim) {
        // Keep the water permission in force for every route shape. The neighbour expansion has
        // its own horizontal guard, but step-up and fall landing also call this predicate; without
        // this early check a no-swim route could still land in a shallow water block whose floor
        // was solid enough to look like ordinary ground.
        if (isWater(level, feet) && !allowSwim) {
            return false;
        }
        if (isPassable(level, feet) && isPassable(level, feet.above())) {
            if (isSolidFloor(level, feet.below()) && !standsHigherThanABlock(level, feet.below())) {
                return true;
            }
            // Only the top water layer is a safe travel surface. Treating every water block with
            // headroom as standable lets an ordinary route descend into a deep river/ocean and
            // leaves the player relying on a late air-recovery while the target keeps pulling it
            // farther from shore.
            return allowSwim && isSurfaceWater(level, feet);
        }
        return false;
    }

    /**
     * Whether a block's collision rises above the block itself: a fence, a wall, a shut gate. Each
     * stands half a block higher than the block it is in, so whatever stands on one has its feet
     * half a block into the block above - which a route of whole blocks cannot describe, and which
     * no step and no jump reaches from the ground beside it. Read as a floor, every fence was a
     * step up, and the cheapest way into a pen was a hop over the fence that no jump clears,
     * rather than the gate.
     */
    public static boolean standsHigherThanABlock(BlockGetter level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) > 1.0 + 1.0E-6;
    }

    /**
     * Finds walkable feet height at a horizontal prospecting coordinate, checking closest heights
     * first. Picking a completely random Y almost always lands in solid stone or mid-air and made
     * prospecting report "nowhere left" even in an ordinary forest.
     */
    public static BlockPos findStandableNearY(BlockGetter level, int x, int z, int preferredY,
                                              int minY, int maxY, int verticalRange) {
        int lower = Math.max(minY, preferredY - Math.max(0, verticalRange));
        int upper = Math.min(maxY, preferredY + Math.max(0, verticalRange));
        if (lower > upper) {
            return null;
        }
        int centreY = Math.clamp(preferredY, lower, upper);
        int furthest = Math.max(centreY - lower, upper - centreY);
        for (int offset = 0; offset <= furthest; offset++) {
            int above = centreY + offset;
            if (above <= upper) {
                BlockPos candidate = new BlockPos(x, above, z);
                if (canStandAt(level, candidate)) {
                    return candidate;
                }
            }
            int below = centreY - offset;
            if (offset > 0 && below >= lower) {
                BlockPos candidate = new BlockPos(x, below, z);
                if (canStandAt(level, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Two blocks of body clearance, ignoring what's underneath (used for falls and swims). */
    public static boolean hasBodyClearance(BlockGetter level, BlockPos feet) {
        return isPassable(level, feet) && isPassable(level, feet.above());
    }

    /**
     * The first block of the player's own body space that is obstructed, or null when it is clear.
     * <p>
     * Walking into a tree is the everyday case: the player ends up standing <em>inside</em> the
     * leaves. The pathfinder never sees this, because it plans from a standable block and models the
     * player as a point, so it keeps returning a perfectly good route whose first step no key press
     * can take. Until this block is gone, nothing about the route matters.
     * <p>
     * {@code supporting} and {@code onGround} exclude the honest case of standing on a tall but
     * walkable block - farmland and dirt paths are under the step height yet reach far enough up
     * that the feet position and the supporting position are the same block.
     * <p>
     * A doorway is never in the way of the body. A door's slab is at the edge of its block, so
     * standing in a doorway is standing beside the door, not inside it - and counting it as
     * inside had the route break the door out of the way, the first time it ever stood in one.
     */
    public static BlockPos blockedBodyPos(BlockGetter level, BlockPos feet, BlockPos supporting,
                                          boolean onGround) {
        if (!isPassable(level, feet) && !(onGround && feet.equals(supporting))
                && !Doorways.isDoorway(level, feet)) {
            return feet;
        }
        BlockPos head = feet.above();
        return isPassable(level, head) || Doorways.isDoorway(level, head) ? null : head;
    }

    /**
     * Every block in the way of one step, in the order {@link PathExecutor} clears them; empty when
     * the step is open.
     *
     * <p>Headroom to jump comes first, and only for a step that goes up. Then the two corner columns
     * of a diagonal, which the player otherwise clips - a leaf, usually. Then the body space at the
     * far end, feet before head. A route can be read with this as well as walked: which block a
     * planned dig would meet first is the same question the executor asks of the step in front of
     * it, and the two must not give different answers.</p>
     *
     * <p>A doorway is never on the list. It is opened ({@link DoorKeeper}), not dug.</p>
     */
    public static List<BlockPos> stepClearance(BlockGetter level, BlockPos from, BlockPos to) {
        List<BlockPos> inTheWay = new ArrayList<>(2);
        if (to.getY() > from.getY()) {
            addUnlessPassable(level, from.above(2), inTheWay);
        }
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        if (dx != 0 && dz != 0) {
            BlockPos sideX = from.offset(dx, 0, 0);
            BlockPos sideZ = from.offset(0, 0, dz);
            addUnlessPassable(level, sideX, inTheWay);
            addUnlessPassable(level, sideX.above(), inTheWay);
            addUnlessPassable(level, sideZ, inTheWay);
            addUnlessPassable(level, sideZ.above(), inTheWay);
        }
        addUnlessPassable(level, to, inTheWay);
        addUnlessPassable(level, to.above(), inTheWay);
        return inTheWay;
    }

    private static void addUnlessPassable(BlockGetter level, BlockPos pos, List<BlockPos> into) {
        if (!isPassable(level, pos) && !Doorways.isDoorway(level, pos)) {
            into.add(pos);
        }
    }

    /**
     * The first block a route would have to dig that {@code canDig} refuses, or null when it
     * refuses none. Read step by step in the order the route would be dug, as
     * {@link #stepClearance} gives it, so the answer is the block the bot would actually stop at.
     */
    public static BlockPos firstUndiggable(BlockGetter level, List<BlockPos> route,
                                           Predicate<BlockState> canDig) {
        for (int step = 1; step < route.size(); step++) {
            for (BlockPos pos : stepClearance(level, route.get(step - 1), route.get(step))) {
                if (!canDig.test(level.getBlockState(pos))) {
                    return pos;
                }
            }
        }
        return null;
    }

    /**
     * Whether a falling player passes straight through this block.
     * <p>
     * Stricter than {@link #isPassable}, and deliberately so: a snow layer or a carpet can be walked
     * over, but it will still stop a fall. Using the walking test for falls made the pathfinder plan
     * drops that simply never happen, leaving the bot standing on top of a block it believed it had
     * already fallen past.
     */
    public static boolean canFallThrough(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (isHarmful(state)) {
            return false;
        }
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    /** Blocks that damage or trap the player. The bot refuses to path through these. */
    public static boolean isHarmful(BlockState state) {
        return state.is(Blocks.LAVA)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE);
    }

    /** True for any fluid, which the bot may swim through at a cost. */
    public static boolean isLiquid(BlockGetter level, BlockPos pos) {
        return !level.getBlockState(pos).getFluidState().isEmpty();
    }

    /** Water, including waterlogged blocks. */
    public static boolean isWater(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState().is(FluidTags.WATER);
    }

    /** Water at the visible surface, where a player can paddle without sinking into the column. */
    public static boolean isSurfaceWater(BlockGetter level, BlockPos pos) {
        return isWater(level, pos)
                && !isWater(level, pos.above())
                && isPassable(level, pos.above());
    }

    /**
     * Water deep enough to swim in rather than wade: part of a column at least two blocks deep.
     *
     * <p>The line between the two movements is whether the eyes can go under, because that is the
     * only place vanilla starts the stroke. One block of water over a floor never covers a
     * standing player's eyes, so it is walked on its bottom at a paddle's pace whatever keys are
     * held. The search prices the two apart on this test and {@link SwimPolicy} decides to dive on
     * it, so the route and the stroke cannot disagree about which water is which.</p>
     */
    public static boolean isDeepWater(BlockGetter level, BlockPos pos) {
        return isWater(level, pos)
                && (isWater(level, pos.above()) || isWater(level, pos.below()));
    }

    /** Lava, including lava held by a modded or waterlog-style block state. */
    public static boolean isLava(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState().is(FluidTags.LAVA);
    }

    /** Ladders, vines and modded blocks in Minecraft's climbable tag. */
    public static boolean isClimbable(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    /** True when water occupies this block or touches one of its six faces. */
    public static boolean nearWater(BlockGetter level, BlockPos pos) {
        if (isWater(level, pos)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (isWater(level, pos.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether lava is at, or immediately touching, this block.
     * <p>
     * Standing next to lava is survivable but a bad place for an unattended bot to be - one mistimed
     * jump or one knockback ends the session. The pathfinder prices this in rather than banning it,
     * so a lava-lined corridor is still usable when it is genuinely the only way through.
     * <p>
     * Deliberately only the six touching faces: this runs for every node the search relaxes, so a
     * wider scan would cost more than the whole rest of the search.
     */
    public static boolean nearLava(BlockGetter level, BlockPos pos) {
        if (isLava(level, pos)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            if (isLava(level, pos.relative(direction))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when removing this solid block would expose its new empty space directly to water.
     * Fluids only begin flowing through a newly broken wall from a face-sharing block, so this is
     * the relevant one-update lookahead rather than a vague wider proximity test.
     */
    public static boolean wouldOpenWater(BlockGetter level, BlockPos pos) {
        return blocksFluid(level, pos) && touchesFluid(level, pos, false);
    }

    /** True when removing this solid block would open a face directly to lava. */
    public static boolean wouldOpenLava(BlockGetter level, BlockPos pos) {
        return blocksFluid(level, pos) && touchesFluid(level, pos, true);
    }

    /**
     * Vanilla's {@code BlockState.blocksMotion()}, which 26.3 removed. The body is the one 26.1.2
     * through 26.2 had: solid by the legacy material rule, except cobweb and bamboo shoots.
     */
    @SuppressWarnings("deprecation")
    public static boolean blocksMotion(BlockState state) {
        Block block = state.getBlock();
        return block != Blocks.COBWEB && block != Blocks.BAMBOO_SAPLING && state.isSolid();
    }

    private static boolean blocksFluid(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && blocksMotion(state) && state.getFluidState().isEmpty();
    }

    private static boolean touchesFluid(BlockGetter level, BlockPos pos, boolean lava) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (lava ? isLava(level, neighbour) : isWater(level, neighbour)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the bot is allowed to mine this block out of the way. Unbreakable blocks report a
     * negative destroy speed; liquids and air are not "breakable" in a useful sense.
     */
    public static boolean isBreakable(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0.0F;
    }

    /**
     * Extra path cost for tunnelling through a block, scaled by hardness so the pathfinder prefers
     * dirt over obsidian and open air over both.
     * <p>
     * Kept close to the cost of walking a block on purpose. Mining a stone block with a decent
     * pickaxe takes a fraction of a second - only a few times longer than walking past it - so
     * pricing it far higher makes A* explore an entire hillside before it will consider digging,
     * and it burns the node budget without ever finding the short route through.
     */
    public static double breakCost(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return Double.POSITIVE_INFINITY;
        }
        if (state.is(BlockTags.LEAVES)) {
            return LEAF_BREAK_COST;
        }
        return 1.0 + hardness * 1.5;
    }

    /** True for sand, gravel, concrete powder, anvils and any other block that falls when unsupported. */
    public static boolean isFallingBlock(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.FallingBlock;
    }

    /**
     * Counts how many consecutive falling blocks sit directly above {@code pos}.
     * This is how much material would collapse if {@code pos} were removed.
     */
    public static int fallingBlocksAbove(BlockGetter level, BlockPos pos) {
        int count = 0;
        BlockPos above = pos.above();
        while (isFallingBlock(level.getBlockState(above))) {
            count++;
            above = above.above();
        }
        return count;
    }

    private MovementHelper() {}
}
