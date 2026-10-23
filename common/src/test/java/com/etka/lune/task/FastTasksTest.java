package com.etka.lune.task;

import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fast tasks, held to what the picker promises: a few small jobs that finish, explain
 * themselves, are guarded where they go out, and stay out of the player's list until one is
 * added - as the player's own.
 *
 * <p>The cards' parameters are checked against the live registry with the starter shelf's, in
 * {@link DefaultTasksParameterTest}; their notes with the shelf's, in
 * {@link SeededAnnotationsTest}.</p>
 */
class FastTasksTest {

    /** The Task tab's rename box; a copy named longer than this could not be typed back. */
    private static final int NAME_BOX_LIMIT = 32;
    /**
     * "Without too much node": the biggest of them is START, four Get Tools cards, End and the
     * guard. A fast task that needs more is a starter job, or a task of the player's own.
     */
    private static final int MOST_CARDS = 7;
    /** The cards that walk, dig or gather outdoors - the ones a fast task guards. */
    private static final Set<String> GOES_OUT = Set.of("chop", "explore", "mine", "gettool",
            "harvest", "loot", "sleep", "recover_death");

    @AfterEach
    void restoreLanguage() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    @Test
    void tenJobsInTheOrderThePickerLaysThemOut() {
        assertEquals(List.of("fast_chop_logs", "fast_mine_deepslate", "fast_mine_iron",
                        "fast_smelt_iron", "fast_stone_tools", "fast_harvest", "fast_eat",
                        "fast_sleep", "fast_store_loot", "fast_last_death"),
                FastTasks.all().stream().map(FastTasks.Fast::id).toList());
        for (FastTasks.Fast fast : FastTasks.all()) {
            TaskGraph task = fast.graph();
            assertEquals(fast.id(), task.seededId, task.name + " is drawn from another id");
            assertTrue(fast.icon().startsWith("minecraft:"), fast.id() + " has no item to show");
            assertEquals(fast, FastTasks.byId(fast.id()).orElse(null));
        }
    }

    /** The three the user named when asking for them (2026-10-15), doing what the names say. */
    @Test
    void theThreeJobsTheUserAskedForAreThere() {
        TaskGraph deepslate = graph(FastTasks.MINE_DEEPSLATE_ID);
        TaskNode mine = deepslate.nodeById("mine");
        assertEquals("mine", mine.commandId);
        assertEquals("minecraft:deepslate", mine.params.get("targets"));
        assertEquals("25", mine.params.get("limit"));

        TaskGraph tools = graph(FastTasks.STONE_TOOLS_ID);
        List<String> made = tools.nodes.stream().filter(node -> "gettool".equals(node.commandId))
                .peek(node -> assertEquals("Stone", node.params.get("material")))
                .map(node -> node.params.get("tool")).toList();
        assertEquals(List.of("Pickaxe", "Axe", "Shovel", "Sword"), made);

        TaskNode recover = graph(FastTasks.LAST_DEATH_ID).nodeById("recover");
        assertEquals("recover_death", recover.commandId);
        assertEquals("Most recent", recover.params.get("which"));
    }

    /**
     * One Mine card's staircase runs out around Y 20 from a sea-level surface, and Mine finishes
     * there with nothing found. The deepslate job counts what it carries and, short, sends a second
     * Mine card on down after it: the same block and the same twenty-five, prospecting, guarded.
     */
    @Test
    void theDeepslateJobCountsAndDigsOnWhenItIsShort() {
        TaskGraph task = graph(FastTasks.MINE_DEEPSLATE_ID);
        TaskNode mine = task.nodeById("mine");
        TaskNode count = task.nodeById("count");
        TaskNode deeper = task.nodeById("deeper");
        assertEquals("count", mine.onSuccess);
        assertEquals("check_item", count.commandId);
        assertEquals("minecraft:cobbled_deepslate", count.params.get("item"));
        assertEquals("At least", count.params.get("comparison"));
        assertEquals("25", count.params.get("count"));
        assertEquals("end", count.onSuccess);
        assertEquals("deeper", count.onFailure);
        assertEquals("mine", deeper.commandId);
        assertEquals("end", deeper.onSuccess);
        for (String param : List.of("targets", "limit", "prospect", "auto_tool")) {
            assertEquals(mine.params.get(param), deeper.params.get(param), "the second pass differs in " + param);
        }
        assertEquals("true", deeper.params.get("prospect"));
        assertEquals(deeper.editorX, count.editorX, "the second pass hangs under the count that sends it");
    }

    @Test
    void everyOneIsSmall() {
        for (TaskGraph task : FastTasks.create()) {
            assertTrue(task.nodes.size() <= MOST_CARDS,
                    task.name + " has " + task.nodes.size() + " cards; a fast task is a handful");
        }
    }

    /**
     * Each fast task's size, pinned. The shelf is built from DefaultTasks' shorthands, so an edit
     * there can change a fast task nobody meant to touch, and this is where that shows.
     */
    @Test
    void everyOneKeepsItsCardCount() {
        assertEquals(List.of(6, 6, 4, 5, 7, 5, 3, 4, 3, 4),
                FastTasks.create().stream().map(task -> task.nodes.size()).toList(),
                "a fast task changed size - check it was meant to, then update this list");
    }

    @Test
    void everyGraphPassesTheSharedAudit() {
        for (TaskGraph task : FastTasks.create()) {
            assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty(),
                    () -> task.name + ": " + TaskConnectionAudit.firstIssue(task)
                            .map(TaskConnectionAudit.Issue::message).orElse("unknown issue"));
        }
    }

    /**
     * An Always, Pulse, Observer or Button card keeps a run alive after its last card, and a card
     * with no Success wire falls through to whatever is next in the list. A fast task has neither,
     * so its run ends with End and the finish alert sounds.
     */
    @Test
    void everyOneFinishes() {
        for (TaskGraph task : FastTasks.create()) {
            TaskNode start = TaskWiring.explicitStart(task);
            assertNotNull(start, task.name + " has no START");
            assertNotNull(start.onSuccess, task.name + " has an unconnected START");
            for (TaskNode node : task.nodes) {
                assertFalse(node.isClockNode() || node.isObserverNode() || node.isButtonNode(),
                        task.name + ": " + node.id + " would keep the run alive after its End");
                if (node.isSourceNode() || node.isPulseNode() || isCompanion(task, node)) {
                    continue;
                }
                assertNotNull(node.onSuccess, task.name + ": " + node.id
                        + " falls through to whatever card is next in the list");
            }
            assertTrue(task.nodes.stream().anyMatch(TaskNode::isEndNode), task.name + " has no End");
        }
    }

    /**
     * A card that could not do its work never wires on to End: the run would finish as though
     * the job were done. Its Fail pin is left open, so the run fails with the card's own reason.
     * Only two failures may end a job: a question's No - "is there raw iron left?" - and a pickup
     * sweep that found nothing left to pick up.
     */
    @Test
    void aFailedCardFailsTheRunRatherThanFinishingIt() {
        Set<String> mayEndOnFail = Set.of("check_item", "loot");
        for (TaskGraph task : FastTasks.create()) {
            for (TaskNode node : task.nodes) {
                TaskNode target = task.nodeById(node.onFailure);
                if (target != null && target.isEndNode()) {
                    assertTrue(mayEndOnFail.contains(node.commandId), task.name + ": " + node.id
                            + " finishes the job when it fails");
                }
            }
        }
    }

    /** The demos' lesson: the cards that go out ride Self Preservation's While pin, and only they. */
    @Test
    void everyCardThatGoesOutIsGuardedAndNothingElseIs() {
        for (TaskGraph task : FastTasks.create()) {
            boolean goesOut = false;
            for (TaskNode node : task.nodes) {
                if (GOES_OUT.contains(node.commandId)) {
                    goesOut = true;
                    assertEquals("guard", node.onWhile,
                            task.name + ": " + node.id + " works outdoors unguarded");
                    assertTrue(node.whileVisible, task.name + ": " + node.id + " hides its guard");
                }
            }
            TaskNode guard = task.nodeById("guard");
            if (!goesOut) {
                assertNull(guard, task.name + " stays put and has a guard to configure for nothing");
                continue;
            }
            assertNotNull(guard, task.name + " goes out unguarded");
            assertEquals("self_preservation", guard.commandId);
            assertEquals(0, guard.repeat, task.name + ": a companion's repeat box is not a lifetime");
        }
    }

    @Test
    void theLaneRunsRightAndNoCardSitsOnAnother() {
        for (TaskGraph task : FastTasks.create()) {
            TaskNode start = TaskWiring.explicitStart(task);
            assertEquals(DefaultTasks.LANE_Y, start.editorY, task.name + ": the lane moved");
            List<TaskNode> lane = task.nodes.stream()
                    .filter(node -> node.editorY.equals(start.editorY))
                    .sorted(Comparator.comparingInt(node -> node.editorX))
                    .toList();
            assertEquals(start, lane.getFirst(), task.name + ": START should lead its lane");
            Set<String> taken = new HashSet<>();
            for (TaskNode node : task.nodes) {
                assertTrue(node.editorY >= start.editorY, task.name + ": " + node.id + " sits above the lane");
                assertTrue(taken.add(node.editorX + "," + node.editorY),
                        task.name + ": " + node.id + " sits on top of another card");
            }
            for (TaskNode node : lane) {
                TaskNode next = task.nodeById(node.onSuccess);
                if (next != null && next.editorY.equals(start.editorY)) {
                    assertTrue(next.editorX > node.editorX,
                            task.name + ": " + node.id + " hands on to a card left of it");
                }
            }
        }
    }

    @Test
    void everyTitleIsTranslatedAndFitsTheRenameBox() {
        List<String> problems = new ArrayList<>();
        for (TaskGraph task : FastTasks.create()) {
            Lang.select("en_us");
            String english = task.displayName();
            if (!english.equals(task.name)) {
                problems.add(task.name + ": the English title is '" + english + "'");
            }
            Lang.select("tr_tr");
            String turkish = task.displayName();
            if (turkish.equals(english)) {
                problems.add(task.name + " has no Turkish title");
            }
            for (String title : List.of(english, turkish)) {
                if (title.length() > NAME_BOX_LIMIT) {
                    problems.add(title + " (" + title.length() + ") overflows the rename box");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** What the picker says a fast task does is the note on its canvas: one line, one key. */
    @Test
    void theDescriptionIsTheNoteInThePlayersLanguage() {
        for (TaskGraph task : FastTasks.create()) {
            assertEquals(1, task.notes.size(), task.name + ": one note says what it does");
            TaskNote note = task.notes.getFirst();
            assertEquals(FastTasks.INTRO, note.id);
            Lang.select("en_us");
            assertEquals(note.text, FastTasks.about(task),
                    task.name + ": the note's English is not its English line");
            Lang.select("tr_tr");
            assertNotEquals(note.text, FastTasks.about(task), task.name + ": the note has no Turkish");
        }
        assertEquals("", FastTasks.about(null));
    }

    /**
     * Add to my tasks hands over the player's own task, the way New Task is theirs: named in their
     * language, with no id, so two copies read as two tasks. The note keeps its id and its
     * language until they edit it.
     */
    @Test
    void aCopyForTheListIsThePlayersOwn() {
        FastTasks.Fast deepslate = FastTasks.byId(FastTasks.MINE_DEEPSLATE_ID).orElseThrow();
        Lang.select("tr_tr");
        TaskGraph copy = FastTasks.forTaskList(deepslate);
        assertNull(copy.seededId);
        assertEquals("25 Kayrak Taşı Kaz", copy.name);
        Lang.select("en_us");
        assertEquals("25 Kayrak Taşı Kaz", copy.displayName(), "a copy keeps the name it was given");
        assertEquals(FastTasks.MINE_DEEPSLATE_ID + "." + FastTasks.INTRO,
                copy.noteById(FastTasks.INTRO).seededId);

        TaskGraph other = FastTasks.forTaskList(deepslate);
        assertNotSame(copy, other);
        copy.nodeById("mine").params.put("limit", "64");
        assertEquals("25", other.nodeById("mine").params.get("limit"),
                "two copies share a card, so changing one changes the other");
        assertEquals("25", deepslate.graph().nodeById("mine").params.get("limit"),
                "a copy's change reached the shelf");
    }

    /**
     * A copy reloaded from the file is matched against the starter shelf's old names by name, and
     * a fast task's id shares a namespace with the shelf's. Neither may collide, or a copy would
     * come back as a starter job and a restore would think a deleted job was still there.
     */
    @Test
    void nothingAboutThemIsMistakenForAStarterJob() {
        Set<String> starterIds = new HashSet<>(DefaultTasks.SEEDED_NAMES.values());
        for (String code : List.of("en_us", "tr_tr")) {
            Lang.select(code);
            for (FastTasks.Fast fast : FastTasks.all()) {
                assertFalse(starterIds.contains(fast.id()), fast.id() + " is a starter job's id");
                TaskGraph copy = FastTasks.forTaskList(fast);
                TaskStore.adoptSeededId(copy);
                assertNull(copy.seededId, copy.name + " reloads as a starter job");
            }
        }
        for (TaskGraph seeded : DefaultTasks.create()) {
            for (TaskGraph fast : FastTasks.create()) {
                assertFalse(TaskStore.alreadyPresent(List.of(fast), seeded),
                        fast.name + " would hide " + seeded.name + " from a restore");
            }
        }
    }

    /** The point of them: never in a new player's list, and never brought back by a restore. */
    @Test
    void theShelfIsNeverSeeded() {
        Set<String> fastIds = new HashSet<>();
        FastTasks.all().forEach(fast -> fastIds.add(fast.id()));
        for (TaskGraph seeded : DefaultTasks.create()) {
            assertFalse(fastIds.contains(seeded.seededId), seeded.name + " is a fast task");
        }
    }

    @Test
    void everyCallBuildsAFreshGraph() {
        TaskGraph first = FastTasks.create().getFirst();
        first.nodes.clear();
        assertFalse(FastTasks.create().getFirst().nodes.isEmpty(),
                "emptying one graph emptied the shelf");
    }

    private static TaskGraph graph(String id) {
        return FastTasks.byId(id).orElseThrow().graph();
    }

    private static boolean isCompanion(TaskGraph task, TaskNode node) {
        return task.nodes.stream().anyMatch(other -> node.id.equals(other.onWhile)
                || other.alwaysTargets.contains(node.id));
    }
}
