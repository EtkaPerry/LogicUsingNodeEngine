package com.etka.lune.client.gui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task list chooses several tasks at once so they can be deleted together, and the open task
 * - the one on the canvas - has to stay one of the chosen rows throughout, or Delete would take a
 * set of tasks other than the one the player is looking at.
 */
class ListSelectionTest {

    private static final List<String> ROWS = List.of("a", "b", "c", "d", "e");

    @Test
    void aPlainClickChoosesOneRowAndOpensIt() {
        ListSelection<String> selection = new ListSelection<>();
        selection.only("b");
        selection.toggle("d");
        selection.only("c");

        assertEquals("c", selection.open());
        assertEquals(List.of("c"), selection.inOrder(ROWS));

        selection.only(null);
        assertNull(selection.open());
        assertTrue(selection.inOrder(ROWS).isEmpty());
    }

    @Test
    void ctrlClickAddsARowAndOpensIt() {
        ListSelection<String> selection = new ListSelection<>();
        selection.only("b");

        assertTrue(selection.toggle("d"), "adding a row opens it");
        assertEquals("d", selection.open());
        assertEquals(List.of("b", "d"), selection.inOrder(ROWS),
                "the row that was open stays chosen, and rows come back in list order");
    }

    @Test
    void ctrlClickTakesOutARowThatIsNotOpenWithoutMovingTheCanvas() {
        ListSelection<String> selection = new ListSelection<>();
        selection.only("a");
        selection.toggle("c");
        selection.toggle("e");

        assertFalse(selection.toggle("c"), "the open row did not change");
        assertEquals("e", selection.open());
        assertEquals(List.of("a", "e"), selection.inOrder(ROWS));
    }

    @Test
    void takingOutTheOpenRowOpensTheOneChosenBeforeIt() {
        ListSelection<String> selection = new ListSelection<>();
        selection.only("a");
        selection.toggle("c");
        selection.toggle("b");

        assertTrue(selection.toggle("b"));
        assertEquals("c", selection.open(), "the most recently chosen of the rest");
        assertEquals(List.of("a", "c"), selection.inOrder(ROWS));

        assertTrue(selection.toggle("c"));
        assertTrue(selection.toggle("a"));
        assertNull(selection.open(), "taking out the last row leaves nothing open");
        assertTrue(selection.inOrder(ROWS).isEmpty());
    }

    @Test
    void shiftClickTakesTheRunFromTheOpenRowEitherWay() {
        ListSelection<String> selection = new ListSelection<>();
        selection.only("b");

        assertFalse(selection.range(ROWS, "d"), "the open row stays open");
        assertEquals("b", selection.open());
        assertEquals(List.of("b", "c", "d"), selection.inOrder(ROWS));

        selection.range(ROWS, "a");
        assertEquals(List.of("a", "b"), selection.inOrder(ROWS),
                "a second Shift+click replaces the run rather than adding to it");
        assertEquals("b", selection.open());
    }

    @Test
    void shiftClickWithNothingOpenIsAPlainClick() {
        ListSelection<String> selection = new ListSelection<>();

        assertTrue(selection.range(ROWS, "c"));
        assertEquals("c", selection.open());
        assertEquals(List.of("c"), selection.inOrder(ROWS));
    }

    @Test
    void selectAllTakesEveryRowAndKeepsTheOpenOne() {
        ListSelection<String> selection = new ListSelection<>();
        selection.only("c");

        selection.all(ROWS);

        assertEquals("c", selection.open());
        assertEquals(ROWS, selection.inOrder(ROWS));
    }

    @Test
    void rowsThatLeaveTheListLeaveTheSelection() {
        ListSelection<String> selection = new ListSelection<>();
        selection.only("a");
        selection.toggle("c");
        selection.toggle("e");

        selection.retain(List.of("a", "b", "c", "d"));

        assertNull(selection.open(), "the open row was deleted, so nothing is open");
        assertEquals(List.of("a", "c"), selection.inOrder(List.of("a", "b", "c", "d")));
    }
}
