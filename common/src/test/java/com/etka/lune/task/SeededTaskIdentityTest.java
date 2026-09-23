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
        assertEquals(List.of("chop_wood", "stone_tools", "go_fishing", "dig_tunnel", "lumber_camp",
                "stone_quarry", "homestead_day", "smeltery", "night_watch", "stuff_back",
                "lit_portal", "ender_dragon", "netherite", "find_village", "cherry_timber"), ids);
    }

    @Test
    void theNameStaysEnglishWhileTheTitleFollowsTheLanguage() {
        TaskGraph chop = DefaultTasks.create().get(0);
        assertEquals("1. Chop Wood", chop.name);
        assertEquals("1. Chop Wood", chop.displayName());

        Lang.select("tr_tr");
        assertEquals("1. Odun Kes", chop.displayName());
        // The half that is written to disk and compared against must not have moved.
        assertEquals("1. Chop Wood", chop.name);
    }

    /**
     * The ladder this shelf replaced is no longer seeded, but players who ran it still have it.
     * Its jobs keep their ids and their translated titles, and none of them is mistaken for a new
     * job - so a restore brings the whole new shelf in beside them.
     */
    @Test
    void theRetiredLadderKeepsItsTitlesAndDoesNotHideTheNewShelf() {
        List<TaskGraph> saved = new ArrayList<>();
        for (String name : List.of("1. Chop 12 Logs", "2. Wood, Pickaxe, 20 Stone",
                "3. Homestead: Farm and Guard", "4. Fish Till Dusk, Then Sleep",
                "5. Stone Tools to a Lit Portal", "6. New World to Ender Dragon")) {
            TaskGraph old = new TaskGraph(name);
            TaskStore.adoptSeededId(old);
            assertNotNull(old.seededId, name + " should still be recognised as a starter job");
            saved.add(old);
        }
        Lang.select("tr_tr");
        assertEquals("1. 12 Kütük Kes", saved.get(0).displayName());

        for (TaskGraph fresh : DefaultTasks.create()) {
            assertFalse(TaskStore.alreadyPresent(saved, fresh),
                    fresh.name + " would be withheld from a player who still has the old ladder");
        }
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
