package com.etka.lune.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Doors and gates read from their own shapes, the way {@link Doorways} reads them. Every door here
 * faces north: its way through runs north and south, along Z.
 */
class DoorwaysTest {

    private static final BlockPos DOOR = new BlockPos(0, 64, 0);
    private static final BlockPos SOUTH = DOOR.south();
    private static final BlockPos NORTH = DOOR.north();
    private static final BlockPos EAST = DOOR.east();

    /** A door's two halves at {@code pos}, on a floor. */
    static TestLevel door(TestLevel level, BlockPos pos, Block block, boolean open) {
        BlockState lower = block.defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.NORTH)
                .setValue(DoorBlock.OPEN, open)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        return level.set(pos.getX(), pos.getY(), pos.getZ(), lower)
                .set(pos.getX(), pos.getY() + 1, pos.getZ(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    static TestLevel gate(TestLevel level, BlockPos pos, boolean open) {
        return level.set(pos.getX(), pos.getY(), pos.getZ(), Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.NORTH)
                .setValue(FenceGateBlock.OPEN, open));
    }

    private static TestLevel ground() {
        return TestLevel.scene().floor(-2, 2, -2, 2, 63);
    }

    @Test
    void aShutDoorIsOpenedToGoThroughIt() {
        TestLevel level = door(ground(), DOOR, Blocks.OAK_DOOR, false);

        assertEquals(Doorways.OPEN_COST, Doorways.stepCost(level, SOUTH, DOOR));
        assertEquals(Doorways.OPEN_COST, Doorways.stepCost(level, NORTH, DOOR));
        assertEquals(DOOR, Doorways.shutAcross(level, SOUTH, DOOR));
        // Leaving it again is paid for already: it was opened on the way in.
        assertEquals(0.0, Doorways.stepCost(level, DOOR, NORTH));
    }

    @Test
    void anOpenDoorIsWalkedThroughButNotAcrossItsSlab() {
        TestLevel level = door(ground(), DOOR, Blocks.OAK_DOOR, true);

        assertEquals(0.0, Doorways.stepCost(level, SOUTH, DOOR), "open is open");
        assertNull(Doorways.shutAcross(level, SOUTH, DOOR), "nothing to open");
        assertTrue(Double.isInfinite(Doorways.stepCost(level, EAST, DOOR)),
                "an open door's slab lies along the way through, and across it is a wall");
    }

    @Test
    void onlyStraightStepsGoThroughADoorway() {
        TestLevel level = door(ground(), DOOR, Blocks.OAK_DOOR, true);

        assertTrue(Double.isInfinite(Doorways.stepCost(level, SOUTH.east(), DOOR)), "diagonal");
        assertTrue(Double.isInfinite(Doorways.stepCost(level, SOUTH.south(), DOOR)), "two along");
        assertTrue(Double.isInfinite(Doorways.stepCost(level, SOUTH.above(2), DOOR)), "two down");
    }

    @Test
    void anIronDoorIsNotADoorwayButABlock() {
        TestLevel level = door(ground(), DOOR, Blocks.IRON_DOOR, false);

        assertFalse(Doorways.isDoorway(level, DOOR), "it needs redstone, and Lune presses no buttons");
        assertNull(Doorways.shutAcross(level, SOUTH, DOOR));
        assertFalse(Doorways.holdsUpADoor(level, DOOR.below()), "and the block under it digs as ever");
    }

    @Test
    void aGateIsOpenedAndThenStandsOutOfTheWay() {
        TestLevel shut = gate(ground(), DOOR, false);
        TestLevel open = gate(ground(), DOOR, true);

        assertEquals(Doorways.OPEN_COST, Doorways.stepCost(shut, SOUTH, DOOR));
        assertEquals(DOOR, Doorways.shutAcross(shut, SOUTH, DOOR));
        assertEquals(0.0, Doorways.stepCost(open, SOUTH, DOOR));
    }

    @Test
    void theDoorIsRememberedByItsLowerHalf() {
        TestLevel level = door(ground(), DOOR, Blocks.OAK_DOOR, false);

        assertEquals(DOOR, Doorways.base(DOOR.above(), level.getBlockState(DOOR.above())));
        assertTrue(Doorways.holdsUpADoor(level, DOOR.below()), "digging the floor takes the door with it");
    }

    @Test
    void standingInADoorwayIsStandingBesideTheDoor() {
        TestLevel shut = door(ground(), DOOR, Blocks.OAK_DOOR, false);
        TestLevel open = door(ground(), DOOR, Blocks.OAK_DOOR, true);

        assertTrue(Doorways.canStandIn(shut, DOOR));
        assertTrue(Doorways.canStandIn(open, DOOR));
        assertNull(MovementHelper.blockedBodyPos(open, DOOR, DOOR.below(), true),
                "nothing in the body's way to dig out");
        assertTrue(MovementHelper.stepClearance(shut, SOUTH, DOOR).isEmpty(),
                "a door is opened, never cleared like a block");
    }

    /**
     * Seen from the far side, a line to the middle of a shut door's block stops short of the slab and
     * touches nothing; the click has to aim at the slab.
     */
    @Test
    void theHandleIsOnTheSlab() {
        TestLevel level = door(ground(), DOOR, Blocks.OAK_DOOR, false);
        BlockPos upper = DOOR.above();
        AABB slab = level.getBlockState(upper).getShape(level, upper).bounds().move(upper);

        Vec3 handle = Doorways.handle(level, DOOR, DOOR.getY() + 1.62);

        assertTrue(slab.inflate(1.0E-6).contains(handle), handle + " is not on " + slab);
        assertEquals(upper.getY(), (int) Math.floor(handle.y), "the half at eye height");
    }
}
