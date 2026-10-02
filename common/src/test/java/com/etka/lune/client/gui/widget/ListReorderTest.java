package com.etka.lune.client.gui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Moving a task by holding it and carrying it to another place in the list.
 *
 * <p>The press that starts a hold is also the click that opens a task, so a good part of this is
 * about what must not move: a click, a press that wandered off, a row let go where it began or
 * beside the list. Pointer positions are in the list's pixels, rows thirteen of them tall; places
 * count from 0.</p>
 */
class ListReorderTest {

    private static final List<String> ROWS = List.of("a", "b", "c", "d");
    private static final long HOLD = ListReorder.HOLD_MILLIS;
    private static final int SLOP = ListReorder.STILL_SLOP;
    /** Where the pointer rests on row b, in place 1. */
    private static final int X = 30;
    private static final int Y = 22;

    /** Row b pressed at time zero and kept still on it until the hold was over. */
    private static ListReorder<String> liftedB() {
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("b", 0);
        reorder.frame(X, Y, 1, 16);
        reorder.frame(X, Y, 1, HOLD);
        return reorder;
    }

    @Test
    void aRowKeptStillLiftsOnlyOnceTheHoldIsOver() {
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("b", 0);

        reorder.frame(X, Y, 1, HOLD - 1);
        assertEquals("b", reorder.holding());
        assertNull(reorder.lifted());

        reorder.frame(X, Y, 1, HOLD);
        assertEquals("b", reorder.lifted());
        assertNull(reorder.holding());
    }

    @Test
    void aClickMovesNothing() {
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("b", 0);
        reorder.frame(X, Y, 1, 80);

        assertNull(reorder.release(ROWS));
        reorder.frame(X, Y, 1, HOLD * 2);
        assertNull(reorder.lifted(), "a row let go is not lifted later by a pointer resting on it");
    }

    @Test
    void wanderingOffBeforeItLiftsLetsGoOfIt() {
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("b", 0);
        reorder.frame(X, Y, 1, 16);
        reorder.frame(X, Y + 13, 2, 300);
        reorder.frame(X, Y, 1, HOLD);

        assertNull(reorder.lifted(), "coming back to the row does not pick the hold up again");
        assertNull(reorder.holding());
        assertNull(reorder.release(ROWS));
    }

    @Test
    void aHandThatOnlyDriftsIsStillKeptStill() {
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("b", 0);
        reorder.frame(X, Y, 1, 16);
        reorder.frame(X + SLOP, Y - SLOP, 1, 500);
        reorder.frame(X - SLOP, Y + SLOP, 1, HOLD);

        assertEquals("b", reorder.lifted());
    }

    @Test
    void aPressAtTheTopOfARowHoldsThatRowAndLiftsItUnmoved() {
        // The press landed on b; every frame after it reads the pointer a pixel higher, over a.
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("b", 0);
        reorder.frame(X, 15, 0, 16);
        reorder.frame(X, 15, 0, HOLD);

        assertEquals("b", reorder.lifted(), "the row pressed is the row lifted");
        assertEquals(-1, reorder.target(ROWS), "the hand never moved, so neither has the row");
        assertNull(reorder.release(ROWS), "and letting go there moves nothing");
    }

    @Test
    void carriedDownItGoesBetweenTheRowsAndTheRestMoveUp() {
        ListReorder<String> reorder = liftedB();
        reorder.frame(X, Y + 13, 2, HOLD + 100);

        assertEquals(List.of("a", "c", "b", "d"), reorder.preview(ROWS), "between c and d");
        assertEquals(new ListReorder.Move<>("b", 2), reorder.release(ROWS));
        assertNull(reorder.lifted(), "the drop ends the gesture");
    }

    @Test
    void carriedUpItGoesBetweenTheRowsAndTheRestMoveDown() {
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("d", 0);
        reorder.frame(X, 48, 3, 16);
        reorder.frame(X, 48, 3, HOLD);
        reorder.frame(X, 9, 0, HOLD + 100);

        assertEquals(List.of("d", "a", "b", "c"), reorder.preview(ROWS), "to the very top");
        reorder.frame(X, 22, 1, HOLD + 200);
        assertEquals(List.of("a", "d", "b", "c"), reorder.preview(ROWS), "between a and b");
        assertEquals(new ListReorder.Move<>("d", 1), reorder.release(ROWS));
    }

    @Test
    void theDropGoesWhereTheLastFrameShowedIt() {
        ListReorder<String> reorder = liftedB();
        reorder.frame(X, Y + 26, 3, HOLD + 100);
        reorder.frame(X, Y + 13, 2, HOLD + 200);

        assertEquals(new ListReorder.Move<>("b", 2), reorder.release(ROWS));
    }

    @Test
    void broughtBackToWhereItBeganNothingMoves() {
        ListReorder<String> reorder = liftedB();
        reorder.frame(X, Y + 26, 3, HOLD + 100);
        reorder.frame(X + 1, Y - 2, 0, HOLD + 200);

        assertSame(ROWS, reorder.preview(ROWS),
                "within a few pixels of the start is home, whichever place that reads as");
        assertNull(reorder.release(ROWS));
    }

    @Test
    void overItsOwnPlaceOrBesideTheListNothingMoves() {
        ListReorder<String> ownPlace = liftedB();
        ownPlace.frame(X + 20, Y, 1, HOLD + 100);
        assertNull(ownPlace.release(ROWS));

        ListReorder<String> beside = liftedB();
        beside.frame(X + 200, Y + 26, -1, HOLD + 100);
        assertSame(ROWS, beside.preview(ROWS), "beside the list the row is shown back where it began");
        assertNull(beside.release(ROWS));
    }

    @Test
    void aRowOnlyHeldPreviewsNothing() {
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("a", 0);
        reorder.frame(X, 9, 0, 16);

        assertSame(ROWS, reorder.preview(ROWS));
        assertEquals(-1, reorder.target(ROWS));
    }

    @Test
    void theHoldShowsOnlyOnceItHasLastedLongerThanAClick() {
        ListReorder<String> reorder = new ListReorder<>();
        reorder.press("a", 1000);

        assertEquals(0.0F, reorder.progress(1000 + ListReorder.SHOW_AFTER_MILLIS - 1));
        assertEquals(0.5F, reorder.progress(1000 + HOLD / 2));
        assertEquals(1.0F, reorder.progress(1000 + HOLD * 3));
        assertEquals(0.0F, new ListReorder<String>().progress(5000), "nothing held, nothing shown");
    }

    @Test
    void rowsAndPlacesTheListNoLongerHasAreLetGo() {
        ListReorder<String> reorder = liftedB();
        reorder.frame(X, Y + 26, 3, HOLD + 100);

        reorder.retain(List.of("a", "b", "c"));
        assertEquals("b", reorder.lifted());
        assertEquals(-1, reorder.target(List.of("a", "b", "c")), "a place past the end is forgotten");

        // An undo puts a fresh copy of every task in the list, the one in hand included.
        reorder.retain(List.of("a", "c"));
        assertNull(reorder.lifted());
        assertNull(reorder.release(List.of("a", "c")));
    }
}
