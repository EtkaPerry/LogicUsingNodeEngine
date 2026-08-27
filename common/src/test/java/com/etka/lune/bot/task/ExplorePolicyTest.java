package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorePolicyTest {

    @Test
    void learnedHeadingHorizonsAlwaysCommitForSeveralStops() {
        assertEquals(2, ExplorePolicy.commitSteps(ExplorePolicy.SHORT_COMMIT));
        assertEquals(3, ExplorePolicy.commitSteps(ExplorePolicy.BALANCED_COMMIT));
        assertEquals(5, ExplorePolicy.commitSteps(ExplorePolicy.LONG_COMMIT));
    }

    @Test
    void wrapsIntoASingleTurn() {
        assertEquals(0.0F, ExplorePolicy.wrap(360.0F), 0.001F);
        assertEquals(-90.0F, ExplorePolicy.wrap(270.0F), 0.001F);
        assertEquals(180.0F, ExplorePolicy.wrap(180.0F), 0.001F);
        assertEquals(10.0F, ExplorePolicy.wrap(730.0F), 0.001F);
    }

    @Test
    void recognisesWalkingBackTheWayItCame() {
        // Walked north (0); south (180) is straight back over the same ground.
        assertTrue(ExplorePolicy.doublesBack(180.0F, 0.0F));
        assertTrue(ExplorePolicy.doublesBack(150.0F, 0.0F));
        assertFalse(ExplorePolicy.doublesBack(90.0F, 0.0F));
    }

    @Test
    void leavesAPerfectlyGoodHeadingAlone() {
        assertEquals(90.0F, ExplorePolicy.avoidDoublingBack(90.0F, 0.0F), 0.001F);
    }

    @Test
    void rotatesAwayFromAReversal() {
        float chosen = ExplorePolicy.avoidDoublingBack(180.0F, 0.0F);

        assertFalse(ExplorePolicy.doublesBack(chosen, 0.0F));
    }

    @Test
    void alsoAvoidsUndoingTheHeadingBeforeLast() {
        // Two 90-degree corrections in a row end up reversing the original; the second-oldest
        // heading is remembered precisely so that cannot happen.
        float chosen = ExplorePolicy.avoidDoublingBack(180.0F, 90.0F, 0.0F);

        assertFalse(ExplorePolicy.doublesBack(chosen, 90.0F, 0.0F));
    }

    @Test
    void copesWithNoHistoryAtAll() {
        assertEquals(45.0F, ExplorePolicy.avoidDoublingBack(45.0F), 0.001F);
        assertEquals(45.0F, ExplorePolicy.avoidDoublingBack(45.0F, (Float) null), 0.001F);
    }

    @Test
    void alwaysReturnsAHeadingEvenWhenEveryDirectionIsGuarded() {
        // Boxed in by history: it still has to walk somewhere rather than stand still.
        float chosen = ExplorePolicy.avoidDoublingBack(0.0F, 180.0F, 90.0F, -90.0F, 0.0F);

        assertEquals(chosen, ExplorePolicy.wrap(chosen), 0.001F);
    }

    @Test
    void doesNotWalkStraightBackIntoAHeadingThatWasJustBlocked() {
        // The coastline case: west is water, so the route fails and south is tried; south is water
        // too. The reversal guard permits west again because west does not reverse south, and the
        // pair then alternates for as long as the phase lasts. A blocked heading has to stay
        // blocked in its own right.
        float chosen = ExplorePolicy.avoidBlocked(90.0F, 90.0F, 0.0F);

        assertFalse(ExplorePolicy.isBlocked(chosen, 90.0F, 0.0F));
    }

    @Test
    void treatsAHeadingNearABlockedOneAsBlockedToo() {
        // Nudging a few degrees off a wall still walks into the wall.
        assertTrue(ExplorePolicy.isBlocked(100.0F, 90.0F));
        assertFalse(ExplorePolicy.isBlocked(170.0F, 90.0F));
    }

    @Test
    void blockedHeadingsWithNoHistoryAreLeftAlone() {
        assertEquals(45.0F, ExplorePolicy.avoidBlocked(45.0F), 0.001F);
        assertEquals(45.0F, ExplorePolicy.avoidBlocked(45.0F, (Float) null), 0.001F);
    }
}
