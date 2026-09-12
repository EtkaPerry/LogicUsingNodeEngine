package com.etka.lune.task;

import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A starter job is two things at once, and this is where they are kept apart.
 *
 * <p>Its name is what it is saved under, what another task's Run Task card points at, and what a
 * save written before ids is recognised by - so it is the same string in every language. Its title
 * is what the player reads, and that is not. Confusing the two gives a Turkish player six duplicate
 * starter jobs the first time they change language, which is not a crash and so is not noticed
 * until the task list has twelve entries in it.</p>
 */
class SeededTaskIdentityTest {

    /** The Task tab's rename box truncates past this, whatever language the title is in. */
    private static final int NAME_BOX_LIMIT = 32;

    @AfterEach
    void restoreLanguage() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    @Test
    void everySeededJobCarriesAnIdAndTheyAreAllDifferent() {
        List<TaskGraph> tasks = DefaultTasks.create();
        List<String> ids = tasks.stream().map(task -> task.seededId).toList();
        assertEquals(List.of("logs", "stone", "homestead", "nightfall", "portal", "dragon"), ids);
    }

    @Test
    void theNameStaysEnglishWhileTheTitleFollowsTheLanguage() {
        TaskGraph logs = DefaultTasks.create().get(0);
        assertEquals("1. Chop 12 Logs", logs.name);
        assertEquals("1. Chop 12 Logs", logs.displayName());

        Lang.select("tr_tr");
        assertEquals("1. 12 Kütük Kes", logs.displayName());
        // The half that is written to disk and compared against must not have moved.
        assertEquals("1. Chop 12 Logs", logs.name);
    }

    @Test
    void renamingMakesItTheirsInEveryLanguage() {
        TaskGraph logs = DefaultTasks.create().get(0);
        logs.name = "Odun işi";
        logs.seededId = null;

        Lang.select("tr_tr");
        assertEquals("Odun işi", logs.displayName());
        Lang.select("en_us");
        assertEquals("Odun işi", logs.displayName());
    }

    /**
     * The migration. A profile that has been running these since before ids gets them back from
     * the name, so the restore below does not decide all six are missing.
     */
    @Test
    void aSaveFromBeforeIdsGetsItsIdsBack() {
        for (TaskGraph seeded : DefaultTasks.create()) {
            TaskGraph old = new TaskGraph(seeded.name);
            assertNull(old.seededId, "a task read from old JSON has no id on it");
            TaskStore.adoptSeededId(old);
            assertEquals(seeded.seededId, old.seededId, old.name);
        }
    }

    @Test
    void aTaskThePlayerNamedIsNotMistakenForASeededOne() {
        TaskGraph mine = new TaskGraph("Woodland Cleanup");
        TaskStore.adoptSeededId(mine);
        assertNull(mine.seededId);
    }

    @Test
    void restoringDoesNotDuplicateJobsThatAreAlreadyThere() {
        List<TaskGraph> saved = new ArrayList<>(DefaultTasks.create());
        for (TaskGraph seeded : DefaultTasks.create()) {
            assertTrue(TaskStore.alreadyPresent(saved, seeded), seeded.name);
        }

        // Renamed, so only the id can still recognise it - which is the whole point of having one.
        saved.get(0).name = "My First Job";
        assertTrue(TaskStore.alreadyPresent(saved, DefaultTasks.create().get(0)));

        // And with neither, it is genuinely gone and should come back.
        saved.remove(0);
        assertFalse(TaskStore.alreadyPresent(saved, DefaultTasks.create().get(0)));
    }

    @Test
    void everyTitleFitsTheRenameBoxInEveryShippedLanguage() {
        List<String> tooLong = new ArrayList<>();
        for (String code : List.of("en_us", "tr_tr")) {
            Lang.select(code);
            for (TaskGraph task : DefaultTasks.create()) {
                String title = task.displayName();
                assertNotNull(title);
                if (title.length() > NAME_BOX_LIMIT) {
                    tooLong.add(code + ": " + title + " (" + title.length() + ")");
                }
            }
        }
        assertTrue(tooLong.isEmpty(),
                "titles the rename box would cut off mid-word:\n" + String.join("\n", tooLong));
    }
}
