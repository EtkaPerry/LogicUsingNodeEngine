package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Doors and fence gates: what a player opens to walk through, and what the route search used to
 * take for walls.
 *
 * <p>The search's idea of a wall is a block whose collision box stands taller than a step, and a
 * door is a full-height slab whether it is open or shut. So every door in the world was a wall, a
 * house was a sealed box, and a route that could dig went in through the front door with a
 * pickaxe. Worse, a bot that ever stood in a doorway was "inside a block", and the step follower
 * broke the door out of its own body space.</p>
 *
 * <p>A door is a slab against one edge of its block. Shut, it lies across the way through; open,
 * it has swung against one side and the way is clear. A closed gate is a bar across the middle of
 * its block, and an open one has no collision at all. So whether a step gets past is a question
 * of which way the step goes, and it is answered here from the block's own collision shape rather
 * than from a table of door geometry - which is also how a modded door, or a gate with a shape of
 * its own, gets read correctly.</p>
 *
 * <p>The rules that follow from it, for the search and the step follower alike:</p>
 * <ul>
 *   <li>A doorway is a door or gate a hand opens: every wooden door, every fence gate. An iron door
 *       needs redstone, and Lune presses no buttons, so to all of this it is a block like any
 *       other - a wall, dug by a route that may dig, as it always was. A stronghold's iron doors
 *       are how Find Portal Room gets through one.</li>
 *   <li>A doorway is walked straight through along its way: level, or a step up or down, never on
 *       a diagonal, never jumped or dropped into. A shut one costs the route {@link #OPEN_COST}.</li>
 *   <li>A doorway is never dug, and nor is the block a door stands on. Opening it is always the
 *       better way through, and digging one out of a player's wall is the opposite of helping.</li>
 * </ul>
 */
public final class Doorways {

    /**
     * What going through a shut door costs a route, in blocks of walking: turning to it and the
     * click, and turning back afterwards to shut it again.
     */
    public static final double OPEN_COST = 4.0;
    /** Half the player's width, which is the corridor a step through a doorway sweeps. */
    private static final double HALF_WIDTH = 0.3;
    private static final AABB ALONG_X = new AABB(0.0, 0.0, 0.5 - HALF_WIDTH, 1.0, 1.0, 0.5 + HALF_WIDTH);
    private static final AABB ALONG_Z = new AABB(0.5 - HALF_WIDTH, 0.0, 0.0, 0.5 + HALF_WIDTH, 1.0, 1.0);
    /** A body standing in the middle of the block. */
    private static final AABB STANDING = new AABB(0.5 - HALF_WIDTH, 0.0, 0.5 - HALF_WIDTH,
            0.5 + HALF_WIDTH, 1.0, 0.5 + HALF_WIDTH);

    private Doorways() {}

    /**
     * A door or a gate a hand opens, open or shut: every fence gate, and every door except the ones
     * that need redstone.
     */
    public static boolean isDoorway(BlockState state) {
        if (state.getBlock() instanceof DoorBlock door) {
            return door.type().canOpenByHand();
        }
        return state.getBlock() instanceof FenceGateBlock;
    }

    public static boolean isDoorway(BlockGetter level, BlockPos pos) {
        return isDoorway(level.getBlockState(pos));
    }

    /** The same block, opened. */
    public static BlockState opened(BlockState state) {
        return state.hasProperty(BlockStateProperties.OPEN)
                ? state.setValue(BlockStateProperties.OPEN, true)
                : state;
    }

    /** Whether it stands open now. */
    public static boolean isOpen(BlockState state) {
        return state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN);
    }

    /** The block that stands for the whole door: a door's lower half, or the gate itself. */
    public static BlockPos base(BlockPos pos, BlockState state) {
        return state.getBlock() instanceof DoorBlock
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                ? pos.below()
                : pos;
    }

    /** Whether a door stands on this block, which is then not to be dug: the door comes off with it. */
    public static boolean holdsUpADoor(BlockGetter level, BlockPos pos) {
        BlockState above = level.getBlockState(pos.above());
        return isDoorway(above) && above.getBlock() instanceof DoorBlock
                && above.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
    }

    /** Whether a body walking along {@code axis}, in the middle of the block, gets past {@code state}. */
    public static boolean passes(BlockGetter level, BlockPos pos, BlockState state, Direction.Axis axis) {
        return clear(state.getCollisionShape(level, pos), axis == Direction.Axis.X ? ALONG_X : ALONG_Z);
    }

    private static boolean clear(VoxelShape shape, AABB body) {
        if (shape.isEmpty()) {
            return true;
        }
        for (AABB box : shape.toAabbs()) {
            if (box.intersects(body)) {
                return false;
            }
        }
        return true;
    }

    /** Whether any of the four body blocks of a step from {@code from} to {@code to} is a doorway. */
    public static boolean touches(BlockGetter level, BlockPos from, BlockPos to) {
        return isDoorway(level, from) || isDoorway(level, from.above())
                || isDoorway(level, to) || isDoorway(level, to.above());
    }

    /**
     * What a step through a doorway costs on top of the walking: nothing when the way stands open,
     * {@link #OPEN_COST} for each door that has to be opened first, and infinite when the step
     * cannot go through at all - across a door's slab, on a diagonal, or up or down by more than a
     * step.
     *
     * <p>A doorway the step is leaving was opened on the way in and paid for then, so it only has
     * to be one the step can leave by. Every other block of the body has to be open, and so does
     * the headroom a step up jumps through, or the space a step down walks out into first.</p>
     */
    public static double stepCost(BlockGetter level, BlockPos from, BlockPos to) {
        Direction.Axis axis = axisOf(from, to);
        if (axis == null) {
            return Double.POSITIVE_INFINITY;
        }
        int rise = to.getY() - from.getY();
        BlockPos headroom = rise > 0 ? from.above(2) : rise < 0 ? to.above(2) : null;
        if (headroom != null && (isDoorway(level, headroom) || !MovementHelper.isPassable(level, headroom))) {
            return Double.POSITIVE_INFINITY;
        }
        double cost = 0.0;
        BlockPos charged = null;
        for (int part = 0; part < 4; part++) {
            boolean leaving = part < 2;
            BlockPos pos = (leaving ? from : to).above(part % 2);
            BlockState state = level.getBlockState(pos);
            if (!isDoorway(state)) {
                if (!leaving && !MovementHelper.isPassable(level, pos)) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            if (passes(level, pos, state, axis)) {
                continue;
            }
            if (!passes(level, pos, opened(state), axis)) {
                return Double.POSITIVE_INFINITY;
            }
            BlockPos door = base(pos, state);
            if (!leaving && !door.equals(charged)) {
                cost += OPEN_COST;
                charged = door;
            }
        }
        return cost;
    }

    /**
     * The doorway the step from {@code from} to {@code to} has to open before it can be taken, as
     * the block that stands for the whole door; null when there is none. Only a door shut across
     * this step's way, and open along it once opened, counts.
     */
    public static BlockPos shutAcross(BlockGetter level, BlockPos from, BlockPos to) {
        Direction.Axis axis = axisOf(from, to);
        if (axis == null) {
            return null;
        }
        for (int part = 0; part < 4; part++) {
            BlockPos pos = (part < 2 ? from : to).above(part % 2);
            BlockState state = level.getBlockState(pos);
            if (isDoorway(state) && !passes(level, pos, state, axis)
                    && passes(level, pos, opened(state), axis)) {
                return base(pos, state);
            }
        }
        return null;
    }

    /**
     * Whether a player can stand in this doorway: a floor under it, and every door in the body's two
     * blocks off to an edge, clear of a body in the middle. A door's slab always is, open or shut,
     * so standing in a doorway is not being stuck in a block - which is what the route search and
     * the step follower both used to decide, before breaking the door out of the way.
     */
    public static boolean canStandIn(BlockGetter level, BlockPos feet) {
        if (!isDoorway(level, feet) && !isDoorway(level, feet.above())) {
            return false;
        }
        for (BlockPos pos : new BlockPos[] {feet, feet.above()}) {
            BlockState state = level.getBlockState(pos);
            boolean fits = isDoorway(state)
                    ? clear(state.getCollisionShape(level, pos), STANDING)
                    : MovementHelper.isPassable(level, pos);
            if (!fits) {
                return false;
            }
        }
        return MovementHelper.isSolidFloor(level, feet.below()) && !isDoorway(level, feet.below());
    }

    /**
     * Where to click a door: the middle of its slab, in the half nearest the eyes. Not the middle of
     * the block - a shut door's slab is at one edge of it, and seen from the other side a line to the
     * block's centre stops short of the slab and touches nothing.
     */
    public static Vec3 handle(BlockGetter level, BlockPos base, double eyeY) {
        BlockPos pos = base;
        if (level.getBlockState(base).getBlock() instanceof DoorBlock
                && eyeY >= base.getY() + 1 && isDoorway(level, base.above())) {
            pos = base.above();
        }
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        Vec3 centre = shape.isEmpty() ? new Vec3(0.5, 0.5, 0.5) : shape.bounds().getCenter();
        return new Vec3(pos.getX() + centre.x, pos.getY() + centre.y, pos.getZ() + centre.z);
    }

    /** The axis of a step one block along, level or a block up or down; null for any other step. */
    private static Direction.Axis axisOf(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(to.getY() - from.getY()) > 1 || Math.abs(dx) + Math.abs(dz) != 1) {
            return null;
        }
        return dx != 0 ? Direction.Axis.X : Direction.Axis.Z;
    }
}
