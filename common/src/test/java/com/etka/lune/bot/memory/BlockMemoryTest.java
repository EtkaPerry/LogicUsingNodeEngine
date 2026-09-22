package com.etka.lune.bot.memory;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A write-off is a fact about a vantage point, not about a block.
 *
 * <p>"I could not get to that log from here" stops being true when the bot is no longer here, and
 * does not stop being true because the log came back into view. Getting that the wrong way round
 * cost a measured run sixty percent of its budget: Explore reported a log, Chop Wood failed to
 * reach it and wrote it off, {@code remember} erased the write-off as Explore saw it again, and the
 * three cards span in a twenty-four tick loop with the bot motionless three blocks from the tree.
 *
 * <p>{@link BlockMemory} is a process-wide singleton, so each test clears what it is about to use
 * rather than assuming an empty one.
 */
class BlockMemoryTest {

    private static final BlockPos LOG = new BlockPos(8, 78, -21);
    private static final BlockPos STANDING = new BlockPos(8, 78, -24);

    // Touching Blocks at all pulls in the registries, and a class that reaches them unbootstrapped
    // does not merely fail itself - it leaves the static state broken for whatever runs next. This
    // test did exactly that once and took four catalog tests down with it.
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void clear() {
        BlockMemory.get().forget(LOG);
    }

    @Test
    void seeingABlockAgainDoesNotClearItsWriteOff() {
        BlockMemory.get().markUnreachable(LOG, STANDING);
        // Explore calls this for every log it can see, from wherever it happens to be standing -
        // which is regularly the exact spot the approach just failed from.
        BlockMemory.get().remember(LOG, Blocks.OAK_LOG);
        assertTrue(BlockMemory.get().isUnreachableFrom(LOG, STANDING),
                "a block back in view from the same place is not a block that became reachable");
        assertTrue(BlockMemory.get().getUnreachable(STANDING).contains(LOG.asLong()),
                "a freshly built task seeds its blacklist from here, so the write-off has to survive");
    }

    @Test
    void movingAwayEarnsAnotherTry() {
        BlockMemory.get().markUnreachable(LOG, STANDING);
        // Far enough that the route which failed is not the route it would take now.
        BlockPos elsewhere = STANDING.offset(20, 0, 20);
        assertFalse(BlockMemory.get().isUnreachableFrom(LOG, elsewhere),
                "a write-off must expire, or one bad angle blacklists a tree for the session");
        assertFalse(BlockMemory.get().getUnreachable(elsewhere).contains(LOG.asLong()));
    }

    @Test
    void shufflingOnTheSpotDoesNotEarnOne() {
        BlockMemory.get().markUnreachable(LOG, STANDING);
        // A block or two of drift is what a stalled approach produces on its own; if that counted
        // as having moved, the loop this whole rule exists to break would still run.
        assertTrue(BlockMemory.get().isUnreachableFrom(LOG, STANDING.offset(1, 0, 1)));
        assertTrue(BlockMemory.get().isUnreachableFrom(LOG, STANDING.offset(0, 0, 2)));
    }

    @Test
    void aWriteOffWithNowhereToReportFromHoldsUntilForgotten() {
        BlockMemory.get().markUnreachable(LOG, null);
        assertTrue(BlockMemory.get().isUnreachableFrom(LOG, STANDING.offset(64, 0, 64)),
                "nothing can expire what has no vantage point recorded, and a retry loop is worse");
        BlockMemory.get().forget(LOG);
        assertFalse(BlockMemory.get().isUnreachableFrom(LOG, STANDING));
    }
}
