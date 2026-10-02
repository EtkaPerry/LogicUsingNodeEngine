package com.etka.lune.bot.task;

import com.etka.lune.bot.util.SightMap;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choosing where to go next in a stronghold, from what has been seen of it.
 *
 * <p>The corridor here runs east from the staircase for forty blocks, with a side passage going
 * south from its middle. Only cells marked as floor can be stood on.</p>
 */
class PortalRoomSearchTest {

    private final Set<BlockPos> floor = new HashSet<>();

    /** Everything round every spot has been looked at: the plain corridor case. */
    private static final PortalRoomSearch.Seen ALL_SEEN = cell -> SightMap.OPEN;

    private PortalRoomSearch corridorWithBranch() {
        for (int x = 0; x <= 40; x++) {
            floor.add(new BlockPos(x, 30, 0));
        }
        for (int z = 1; z <= 20; z++) {
            floor.add(new BlockPos(20, 30, z));
        }
        PortalRoomSearch search = new PortalRoomSearch(0, 0, 40);
        for (BlockPos cell : floor) {
            // What the eyes pass through is the air above the floor; the search asks about both.
            search.seen(cell.above().asLong(), floor::contains);
        }
        return search;
    }

    @Test
    void onlySpotsThatCanBeStoodOnAreCandidates() {
        PortalRoomSearch search = corridorWithBranch();
        assertEquals(floor.size(), search.frontierSize());
    }

    @Test
    void afterLookingRoundItHeadsFarDownTheCorridorRatherThanAStepAtATime() {
        PortalRoomSearch search = corridorWithBranch();
        BlockPos start = new BlockPos(0, 30, 0);
        search.looked(start, PortalRoomSearch.LOOKED_ROUND, ALL_SEEN);
        BlockPos next = search.next(start, 1.0, 0.0);
        assertNotNull(next);
        assertEquals(0, next.getZ());
        assertTrue(next.getX() >= 18 && next.getX() <= 30, "went to " + next);
    }

    @Test
    void itKeepsGoingTheWayItWasGoingBeforeTurningBack() {
        PortalRoomSearch search = corridorWithBranch();
        search.looked(new BlockPos(0, 30, 0), PortalRoomSearch.LOOKED_ROUND, ALL_SEEN);
        // Walked east to x = 24, glancing about on the way.
        for (int x = 3; x <= 24; x += 3) {
            search.looked(new BlockPos(x, 30, 0), PortalRoomSearch.WALKED_PAST, ALL_SEEN);
        }
        BlockPos here = new BlockPos(24, 30, 0);
        search.looked(here, PortalRoomSearch.LOOKED_ROUND, ALL_SEEN);
        BlockPos next = search.next(here, 1.0, 0.0);
        assertNotNull(next);
        assertTrue(next.getX() > 28 && next.getZ() == 0, "should carry on east, went to " + next);
    }

    @Test
    void theSidePassageIsWhatIsLeftOnceTheCorridorIsDone() {
        PortalRoomSearch search = corridorWithBranch();
        for (int x = 0; x <= 40; x += 3) {
            search.looked(new BlockPos(x, 30, 0), PortalRoomSearch.LOOKED_ROUND, ALL_SEEN);
        }
        BlockPos next = search.next(new BlockPos(40, 30, 0), 1.0, 0.0);
        assertNotNull(next);
        assertEquals(20, next.getX());
        assertTrue(next.getZ() > 5, "went to " + next);
    }

    @Test
    void aRefusedSpotIsNotOfferedAgainAndNothingLeftMeansNull() {
        PortalRoomSearch search = new PortalRoomSearch(0, 0, 40);
        BlockPos only = new BlockPos(10, 30, 10);
        floor.add(only);
        search.seen(only.above().asLong(), floor::contains);
        assertTrue(search.hasFrontier());
        search.refuse(only);
        assertFalse(search.hasFrontier());
        assertNull(search.next(BlockPos.ZERO, 0.0, 0.0));
        search.seen(only.above().asLong(), floor::contains);
        assertFalse(search.hasFrontier(), "a refused spot stays refused");
    }

    @Test
    void lookingRoundDoesNotVisitTheFloorBelow() {
        // Dug down onto the staircase's roof: the steps seen through the hole are right below,
        // and standing on the roof looking at them is not the same as having been down them.
        PortalRoomSearch search = new PortalRoomSearch(0, 0, 40);
        BlockPos step = new BlockPos(1, 25, 1);
        floor.add(step);
        search.seen(step.above().asLong(), floor::contains);
        search.looked(new BlockPos(0, 30, 0), PortalRoomSearch.LOOKED_ROUND, ALL_SEEN);
        assertTrue(search.hasFrontier(), "the stairwell under the hole is still to be visited");
        assertEquals(step, search.next(new BlockPos(0, 30, 0), 0.0, 0.0));
    }

    @Test
    void aStepWhoseWayDownIsUnseenIsNotDoneWithJustBecauseItWasNear() {
        // The top of a spiral stair: the next step is right here, and what is below it is not seen.
        PortalRoomSearch search = new PortalRoomSearch(0, 0, 40);
        BlockPos step = new BlockPos(2, 29, 1);
        floor.add(step);
        search.seen(step.above().asLong(), floor::contains);
        BlockPos below = new BlockPos(3, 28, 1);
        search.looked(new BlockPos(0, 30, 0), PortalRoomSearch.LOOKED_ROUND,
                cell -> cell == below.asLong() ? SightMap.UNSEEN : SightMap.OPEN);
        assertTrue(search.hasFrontier(), "the way down from the step is still unseen");
        search.looked(new BlockPos(2, 29, 1), PortalRoomSearch.LOOKED_ROUND, ALL_SEEN);
        assertFalse(search.hasFrontier(), "stood on it with everything round it seen: done");
    }

    @Test
    void aWallBesideASpotIsAnAnswerEvenThoughNobodyCanSeeInsideIt() {
        PortalRoomSearch search = new PortalRoomSearch(0, 0, 40);
        BlockPos spot = new BlockPos(1, 30, 1);
        floor.add(spot);
        search.seen(spot.above().asLong(), floor::contains);
        // East is a wall; everything else is open and seen; the wall's own insides are not.
        BlockPos wall = spot.east();
        search.looked(new BlockPos(0, 30, 0), PortalRoomSearch.LOOKED_ROUND, cell -> {
            if (cell == wall.asLong()) {
                return SightMap.STOPPED;
            }
            return cell == wall.below().asLong() || cell == wall.above().asLong()
                    ? SightMap.UNSEEN : SightMap.OPEN;
        });
        assertFalse(search.hasFrontier());
    }

    @Test
    void aSpotStoodOnIsDoneWithEvenWhenSomethingThereStaysUnanswered() {
        PortalRoomSearch search = new PortalRoomSearch(0, 0, 40);
        BlockPos spot = new BlockPos(1, 30, 1);
        floor.add(spot);
        search.seen(spot.above().asLong(), floor::contains);
        search.looked(spot, PortalRoomSearch.LOOKED_ROUND, cell -> SightMap.UNSEEN);
        assertTrue(search.hasFrontier());
        search.visited(spot);
        assertFalse(search.hasFrontier());
    }

    @Test
    void anExcludedCellIsNeverACandidate() {
        PortalRoomSearch search = new PortalRoomSearch(0, 0, 40);
        BlockPos shaftBottom = new BlockPos(4, 30, 4);
        floor.add(shaftBottom);
        search.exclude(shaftBottom);
        search.seen(shaftBottom.above().asLong(), floor::contains);
        assertFalse(search.hasFrontier());
    }

    @Test
    void nothingAboveTheCeilingOrOutOfReachIsACandidate() {
        PortalRoomSearch search = new PortalRoomSearch(0, 0, 40);
        BlockPos high = new BlockPos(5, 60, 5);
        BlockPos far = new BlockPos(500, 30, 0);
        floor.add(high);
        floor.add(far);
        search.seen(high.above().asLong(), floor::contains);
        search.seen(far.above().asLong(), floor::contains);
        assertFalse(search.hasFrontier());
    }
}
