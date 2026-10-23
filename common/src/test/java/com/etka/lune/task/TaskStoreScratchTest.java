package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A graph the editor holds for somebody else - a Lumi's own - sits in the store while it is open, so
 * the editor and its undo treat it as any task, and is never the player's: never listed, never
 * written to the file, gone when it is let go.
 */
class TaskStoreScratchTest {

    private static TaskStore storeWith(TaskGraph... tasks) {
        TaskStore store = new TaskStore();
        store.restoreAll(TaskJson.PRETTY.toJson(List.of(tasks)));
        return store;
    }

    @Test
    void aHeldGraphIsNeitherListedNorWrittenButIsThere() {
        TaskStore store = storeWith(new TaskGraph("Chop Wood"));
        TaskGraph held = store.adoptScratch(new TaskGraph("ignored"), "Pip's nodes");

        assertTrue(store.isScratch(held));
        assertEquals(List.of("Chop Wood"), store.listed().stream().map(task -> task.name).toList());
        assertEquals(List.of("Chop Wood"), store.saved().stream().map(task -> task.name).toList());
        assertTrue(store.names().contains(held.name), "the editor still finds it by name");
    }

    @Test
    void itTakesANameOfItsOwnAndLetsGoCleanly() {
        TaskStore store = storeWith(new TaskGraph("Pip's nodes"));
        TaskGraph held = store.adoptScratch(new TaskGraph("x"), "Pip's nodes");
        assertNotEquals("Pip's nodes", held.name, "never the name of one of the player's tasks");
        assertFalse(store.isScratch(store.byName("Pip's nodes").orElseThrow()));

        store.dropScratch(held.name);
        assertFalse(store.names().contains(held.name));
        assertFalse(store.isScratch(held));
    }

    @Test
    void anUndoThatRestoresTheStoreFromACopyStillKnowsWhichGraphIsHeld() {
        TaskStore store = storeWith(new TaskGraph("Chop Wood"));
        TaskGraph held = store.adoptScratch(new TaskGraph("x"), "Pip's nodes");
        String snapshot = store.exportAll();
        store.restoreAll(snapshot);
        TaskGraph restored = store.byName(held.name).orElseThrow();
        assertTrue(store.isScratch(restored));
        assertFalse(store.listed().contains(restored));
    }
}
