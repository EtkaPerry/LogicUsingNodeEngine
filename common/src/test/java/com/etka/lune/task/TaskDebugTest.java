package com.etka.lune.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a held run obeys.
 *
 * <p>{@link TaskDebug} is static because there is one run, so each test starts from a cleared
 * state and an armed debugger rather than from whatever the previous one left behind.</p>
 */
class TaskDebugTest {

    private TaskGraph task;
    private TaskNode first;
    private TaskNode second;

    @BeforeEach
    void reset() {
        TaskDebug.clear();
        TaskDebug.setArmed(true);
        task = new TaskGraph("Debugging");
        first = new TaskNode("mine");
        second = new TaskNode("goto");
        task.nodes.add(first);
        task.nodes.add(second);
    }

    @AfterEach
    void tidy() {
        TaskDebug.clear();
        TaskDebug.setArmed(true);
    }

    @Test
    void arrivingAtAPlainCardDoesNotHold() {
        assertFalse(TaskDebug.arrive(first));
        assertFalse(TaskDebug.holding());
    }

    @Test
    void arrivingAtABreakpointHolds() {
        first.breakpoint = true;

        assertTrue(TaskDebug.arrive(first));
        assertTrue(TaskDebug.holding());
        assertEquals(first.id, TaskDebug.haltedNodeId());
        assertTrue(TaskDebug.isHalted(first));
        assertFalse(TaskDebug.isHalted(second));
    }

    @Test
    void stepRunsTheHeldCardAndHoldsAtTheNextOne() {
        first.breakpoint = true;
        TaskDebug.arrive(first);

        TaskDebug.step();

        // Released here, so the held card actually runs...
        assertFalse(TaskDebug.holding());
        // ...and the next card holds even though nobody put a breakpoint on it.
        assertTrue(TaskDebug.arrive(second));
        assertEquals(second.id, TaskDebug.haltedNodeId());
        assertEquals(1, TaskDebug.stepsTaken());
    }

    @Test
    void continueRunsOnPastCardsWithoutBreakpoints() {
        first.breakpoint = true;
        TaskDebug.arrive(first);

        TaskDebug.resume();

        assertFalse(TaskDebug.holding());
        assertFalse(TaskDebug.arrive(second));
        assertFalse(TaskDebug.holding());
    }

    @Test
    void continueStillStopsAtTheNextBreakpoint() {
        first.breakpoint = true;
        second.breakpoint = true;
        TaskDebug.arrive(first);

        TaskDebug.resume();

        assertTrue(TaskDebug.arrive(second));
    }

    @Test
    void stepOnAFreeRunningTaskArmsAHoldAtTheNextCard() {
        TaskDebug.step();

        assertFalse(TaskDebug.holding());
        assertTrue(TaskDebug.waitingToBreak());
        assertTrue(TaskDebug.arrive(first));
        assertFalse(TaskDebug.waitingToBreak());
    }

    @Test
    void disarmingIgnoresBreakpointsAndReleasesAHoldInProgress() {
        first.breakpoint = true;
        TaskDebug.arrive(first);
        assertTrue(TaskDebug.holding());

        TaskDebug.setArmed(false);

        // A run frozen by a breakpoint the player has just switched off would have nothing left
        // on screen claiming responsibility for it.
        assertFalse(TaskDebug.holding());
        assertFalse(TaskDebug.arrive(first));
    }

    @Test
    void theRunEndingClearsTheHoldButNotThePlayersBreakpoints() {
        first.breakpoint = true;
        TaskDebug.arrive(first);

        TaskDebug.clear();

        assertFalse(TaskDebug.holding());
        assertTrue(first.breakpoint);
        assertEquals(1, TaskDebug.count(task));
    }

    @Test
    void countingAndClearingCoverTheWholeTask() {
        first.breakpoint = true;
        second.breakpoint = true;

        assertEquals(2, TaskDebug.count(task));
        assertEquals(2, TaskDebug.clearAll(task));
        assertEquals(0, TaskDebug.count(task));
    }

    @Test
    void togglingReportsWhatTheCardEndedUpAs() {
        assertTrue(TaskDebug.toggle(first));
        assertTrue(first.breakpoint);
        assertFalse(TaskDebug.toggle(first));
        assertFalse(first.breakpoint);
    }

    @Test
    void anEmptyLaneIsNotSomethingToHoldAt() {
        // advance() hands a null node in whenever a circuit has run out of cards.
        assertFalse(TaskDebug.arrive(null));
        assertFalse(TaskDebug.holding());
    }
}
