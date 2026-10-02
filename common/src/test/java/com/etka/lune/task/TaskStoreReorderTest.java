package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A task carried to another place in a list keeps that place in the file, where both lists read
 * their order.
 *
 * <p>The lists show only the listed tasks, so two rows side by side on screen can have a starter
 * job between them in the file that waits for a mod the pack does not have.</p>
 */
class TaskStoreReorderTest {

    @Test
    void theListedTasksTakeTheNewOrderAndAHiddenJobKeepsItsPlace() {
        TaskGraph chop = new TaskGraph("1. Chop Wood");
        TaskGraph fish = new TaskGraph("3. Go Fishing");
        TaskGraph hidden = new TaskGraph("14. Find a Village");
        TaskGraph mine = new TaskGraph("My Mine");
        List<TaskGraph> tasks = new ArrayList<>(List.of(chop, fish, hidden, mine));

        // On screen the list reads chop, fish, mine, and mine is carried to the top.
        assertTrue(TaskStore.reorder(tasks, List.of(mine, chop, fish)));

        assertEquals(List.of(mine, chop, hidden, fish), tasks);
    }

    @Test
    void anOrderThatChangesNothingOrDoesNotMatchTheListMovesNothing() {
        TaskGraph first = new TaskGraph("First");
        TaskGraph second = new TaskGraph("Second");
        List<TaskGraph> tasks = new ArrayList<>(List.of(first, second));

        assertFalse(TaskStore.reorder(tasks, List.of(first, second)), "the same order");
        assertFalse(TaskStore.reorder(tasks, List.of(second, new TaskGraph("First"))),
                "a task of the same name is still another task");
        assertFalse(TaskStore.reorder(tasks, List.of(second, second)), "one task twice");

        assertEquals(List.of(first, second), tasks);
    }
}
