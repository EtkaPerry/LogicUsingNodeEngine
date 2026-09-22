package com.etka.lune.bot.task;

import com.etka.lune.bot.path.TestLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two questions the Collect Dragon Egg card asks of the ground: can the egg be dropped where
 * it stands, and where does the bot stand to do it.
 *
 * <p>Both are asked of a real scene rather than restated here, because the whole point of the card
 * is that the answer changes with what is under the egg - bedrock on the fountain, end stone on
 * the island, and a hole in either.</p>
 */
class DragonEggPolicyTest {

    private static final BlockPos EGG = new BlockPos(0, 64, 0);

    /** Flat ground with the egg sitting on top of it, which is the ordinary case. */
    private static TestLevel island() {
        return TestLevel.scene()
                .fill(-4, 4, 56, 63, -4, 4, Blocks.END_STONE)
                .set(EGG.getX(), EGG.getY(), EGG.getZ(), Blocks.DRAGON_EGG);
    }

    @Test
    void theTorchGoesTwoBelowAndTheSupportIsTheBlockUnderIt() {
        assertEquals(new BlockPos(0, 62, 0), DragonEggTask.torchSpot(EGG));
        assertEquals(new BlockPos(0, 63, 0), DragonEggTask.supportOf(EGG));
    }

    @Test
    void endStoneUnderTheEggCanBeWorkedWhereItStands() {
        assertTrue(DragonEggTask.canExtractHere(island(), EGG));
    }

    @Test
    void bedrockUnderTheEggMeansItHasToBeMovedFirst() {
        // The exit fountain: the egg spawns on bedrock, so there is nowhere to put a torch and the
        // only thing the card can do is punch it onto the island.
        TestLevel fountain = TestLevel.scene()
                .fill(-2, 2, 56, 63, -2, 2, Blocks.BEDROCK)
                .set(EGG.getX(), EGG.getY(), EGG.getZ(), Blocks.DRAGON_EGG);
        assertFalse(DragonEggTask.canExtractHere(fountain, EGG));
    }

    @Test
    void bedrockOneBlockLowerIsAlsoRefused() {
        // Diggable on top, unbreakable where the torch has to go.
        TestLevel level = island();
        level.set(0, 62, 0, Blocks.BEDROCK);
        assertFalse(DragonEggTask.canExtractHere(level, EGG));
    }

    @Test
    void aHoleUnderThePocketIsRefusedBecauseTheEggWouldFallPastTheTorch() {
        TestLevel level = island();
        level.carve(0, 0, 61, 61, 0, 0);
        assertFalse(DragonEggTask.canExtractHere(level, EGG));
    }

    @Test
    void waterUnderThePocketIsRefusedForTheSameReason() {
        // isFree counts liquids: a falling block goes straight through them.
        TestLevel level = island();
        level.set(0, 61, 0, Blocks.WATER);
        assertFalse(DragonEggTask.canExtractHere(level, EGG));
    }

    @Test
    void theStanceIsAStraightNeighbourOfTheEgg() {
        BlockPos stance = DragonEggTask.stanceColumn(island(), EGG, new BlockPos(3, 64, 0), 0);
        assertNotNull(stance);
        assertEquals(EGG.getY(), stance.getY(), "the bot stands at the egg's own height first");
        int dx = Math.abs(stance.getX() - EGG.getX());
        int dz = Math.abs(stance.getZ() - EGG.getZ());
        assertEquals(1, dx + dz, "diagonals put a corner between the eye and the work");
    }

    @Test
    void theNearestWorkableNeighbourIsChosen() {
        assertEquals(new BlockPos(1, 64, 0),
                DragonEggTask.stanceColumn(island(), EGG, new BlockPos(6, 64, 0), 0));
        assertEquals(new BlockPos(0, 64, 1),
                DragonEggTask.stanceColumn(island(), EGG, new BlockPos(0, 64, 6), 0));
    }

    @Test
    void aNeighbourWithNothingToStandOnAtTheBottomIsNotAStance() {
        // Every straight neighbour is a shaft with no floor under it: digging down three there is
        // a fall of unknown depth, not a stance.
        TestLevel level = island();
        for (BlockPos side : new BlockPos[]{
                EGG.east(), EGG.west(), EGG.north(), EGG.south()}) {
            level.carve(side.getX(), side.getX(), 40, 60, side.getZ(), side.getZ());
        }
        assertNull(DragonEggTask.stanceColumn(level, EGG, new BlockPos(3, 64, 0), 0));
    }

    @Test
    void aNeighbourTheBotCannotStandInIsNotAStance() {
        // Walled in on three sides; only the open one can be walked to and dug down in.
        TestLevel level = island();
        level.fill(1, 1, 64, 65, 0, 0, Blocks.END_STONE);
        level.fill(-1, -1, 64, 65, 0, 0, Blocks.END_STONE);
        level.fill(0, 0, 64, 65, -1, -1, Blocks.END_STONE);
        assertEquals(new BlockPos(0, 64, 1),
                DragonEggTask.stanceColumn(level, EGG, new BlockPos(0, 64, 4), 0));
    }

    @Test
    void oneBlockDeeperNeedsOneBlockMoreOfGroundUnderIt() {
        // The escalation used when a break is refused because the ray hits the egg's support: the
        // same column, one deeper, which only works if the ground goes that far down.
        TestLevel shallow = TestLevel.scene()
                .fill(-4, 4, 60, 63, -4, 4, Blocks.END_STONE)
                .set(EGG.getX(), EGG.getY(), EGG.getZ(), Blocks.DRAGON_EGG);
        assertNotNull(DragonEggTask.stanceColumn(shallow, EGG, new BlockPos(3, 64, 0), 0));
        assertNull(DragonEggTask.stanceColumn(shallow, EGG, new BlockPos(3, 64, 0), 1));
    }
}
