package com.etka.lune.bot.memory;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The note Chop Wood leaves for Replant Trees: where each tree stood, in which world.
 *
 * <p>Each test builds its own ledger rather than sharing the singleton, and stands in a plain
 * object for the world, because all the ledger asks of a world is whether it is the same one.</p>
 */
class StumpLedgerTest {

    private static final Object OVERWORLD = new Object();
    private static final Object NETHER = new Object();

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void stumpsComeBackNearestFirstAndOnlyWithinReach() {
        StumpLedger ledger = new StumpLedger();
        ledger.note(OVERWORLD, new BlockPos(10, 64, 0), Blocks.OAK_LOG);
        ledger.note(OVERWORLD, new BlockPos(3, 64, 0), Blocks.BIRCH_LOG);
        ledger.note(OVERWORLD, new BlockPos(60, 64, 0), Blocks.OAK_LOG);

        List<StumpLedger.Stump> near = ledger.near(OVERWORLD, BlockPos.ZERO.above(64), 32);
        assertEquals(List.of(new BlockPos(3, 64, 0), new BlockPos(10, 64, 0)),
                near.stream().map(StumpLedger.Stump::pos).toList());
        assertEquals(Blocks.BIRCH_LOG, near.getFirst().log(), "the wood is kept for the right sapling");
    }

    @Test
    void aPlantedStumpIsCrossedOffAndANoteTwiceIsOneNote() {
        StumpLedger ledger = new StumpLedger();
        BlockPos stump = new BlockPos(1, 70, 1);
        ledger.note(OVERWORLD, stump, Blocks.OAK_LOG);
        ledger.note(OVERWORLD, stump, Blocks.SPRUCE_LOG);
        assertEquals(1, ledger.size());
        assertEquals(Blocks.SPRUCE_LOG, ledger.near(OVERWORLD, stump, 4).getFirst().log(),
                "the later note describes what stood there last");

        ledger.forget(stump);
        assertTrue(ledger.near(OVERWORLD, stump, 4).isEmpty());
    }

    /** A position means nothing in another world, and planting there would be planting at random. */
    @Test
    void anotherWorldStartsAnEmptyLedger() {
        StumpLedger ledger = new StumpLedger();
        ledger.note(OVERWORLD, new BlockPos(5, 64, 5), Blocks.OAK_LOG);

        assertTrue(ledger.near(NETHER, new BlockPos(5, 64, 5), 16).isEmpty());
        assertEquals(0, ledger.size(), "asking about another world empties the ledger");
        assertTrue(ledger.near(OVERWORLD, new BlockPos(5, 64, 5), 16).isEmpty(),
                "and coming back does not bring the old notes back");
    }

    @Test
    void aFullLedgerDropsItsOldestNote() {
        StumpLedger ledger = new StumpLedger();
        for (int i = 0; i <= StumpLedger.CAPACITY; i++) {
            ledger.note(OVERWORLD, new BlockPos(i, 64, 0), Blocks.OAK_LOG);
        }
        assertEquals(StumpLedger.CAPACITY, ledger.size());
        assertTrue(ledger.near(OVERWORLD, new BlockPos(0, 64, 0), 0).isEmpty(),
                "the first tree felled is the one forgotten");
    }
}
