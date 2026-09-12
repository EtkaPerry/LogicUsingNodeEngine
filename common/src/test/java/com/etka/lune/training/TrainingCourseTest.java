package com.etka.lune.training;

import com.etka.lune.task.TaskConnectionAudit;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskSignalLink;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves each puzzle is a puzzle: broken when handed over, and whole once the intended card is
 * wired in.
 *
 * <p>The fixes below are written out by hand rather than taken from the lesson, which is the point.
 * A lesson that carried its own solved graph would pass any marking rule it also defined; these
 * assemble the answer the way a player would - drop the card, wire the pin - and then ask the same
 * {@link TaskConnectionAudit} the editor asks.</p>
 */
class TrainingCourseTest {

    @Test
    void everyStarterIsBrokenAndSaysWhy() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            TaskGraph attempt = lesson.newAttempt();
            assertFalse(lesson.isSolvedBy(attempt),
                    lesson.id() + " is handed to the player already solved");
            assertFalse(lesson.critique(attempt).isBlank(),
                    lesson.id() + " gives the player nothing to go on");
            assertTrue(com.etka.lune.task.TaskStore.isTrainingAttempt(attempt),
                    lesson.id() + " is not marked as scratch, so the store would keep it and it"
                            + " would sit in the task list looking like the player's own work");
        }
    }

    @Test
    void everyPuzzleIsSolvedByTheCardItAsksFor() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            TaskGraph attempt = lesson.newAttempt();
            solve(lesson.id(), attempt);
            assertTrue(TaskConnectionAudit.firstIssue(attempt).isEmpty(),
                    lesson.id() + " is still structurally broken after the intended fix: "
                            + TaskConnectionAudit.firstIssue(attempt)
                            .map(TaskConnectionAudit.Issue::message).orElse(""));
            assertTrue(lesson.isSolvedBy(attempt),
                    lesson.id() + " does not accept its own answer");
            assertEquals("", lesson.critique(attempt),
                    lesson.id() + " still complains about a solved routine");
        }
    }

    @Test
    void luneAnswerRevealBuildsEveryLesson() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            TaskGraph attempt = lesson.newAttempt();
            TrainingAnswer.applyCard(lesson, attempt);
            TrainingAnswer.solve(lesson, attempt);
            assertTrue(lesson.isSolvedBy(attempt),
                    lesson.id() + " is not solved by the answer Lune reveals");
        }
    }

    /** Deleting the broken half is not a solution, and the marking must not accept it as one. */
    @Test
    void emptyingTheCanvasIsNotASolution() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            TaskGraph attempt = lesson.newAttempt();
            attempt.nodes.clear();
            attempt.cableAnchors.clear();
            assertFalse(lesson.isSolvedBy(attempt),
                    lesson.id() + " is solved by throwing the routine away");
        }
    }

    /** Dropping the right card without wiring it in is not a solution either. */
    @Test
    void anUnwiredAnswerIsNotASolution() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            TaskGraph attempt = lesson.newAttempt();
            TaskNode loose = new TaskNode(lesson.answerCommandId());
            loose.id = "loose";
            loose.editorX = 24;
            loose.editorY = 400;
            attempt.nodes.add(loose);
            assertFalse(lesson.isSolvedBy(attempt),
                    lesson.id() + " accepts the answer card sitting unconnected on the canvas");
        }
    }

    /**
     * The exact mistake the ordering lesson exists to correct must not be marked right.
     *
     * <p>This routine passes the audit - every pin connected, every card powered - and swings bare
     * hands at stone for the whole run before picking up the pickaxe. Structural correctness is not
     * the same as being in the right place, and this is the one lesson where the difference is the
     * entire point.</p>
     */
    @Test
    void aToolWiredInAfterTheCardThatNeedsItIsNotASolution() {
        TrainingLesson lesson = TrainingCourse.byId("tool").orElseThrow();
        TaskGraph attempt = lesson.newAttempt();
        TaskNode tool = add(attempt, "gettool", "answer");
        TaskNode mine = attempt.nodeById("mine");
        tool.onSuccess = attempt.nodeById("loot").id;
        tool.onFailure = attempt.nodeById("loot").id;
        mine.onSuccess = tool.id;
        mine.onFailure = tool.id;

        assertTrue(TaskConnectionAudit.firstIssue(attempt).isEmpty(),
                "the trap only works if the wrong-order routine is structurally fine");
        assertFalse(lesson.isSolvedBy(attempt),
                "a pickaxe fetched after the mining was marked correct");
    }

    @Test
    void everyLessonOffersTheAnswerAndTwoDistinctDecoys() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            List<String> choices = lesson.choices();
            assertEquals(3, choices.size(), lesson.id() + " should offer exactly three cards");
            assertEquals(3, Set.copyOf(choices).size(), lesson.id() + " repeats a card");
            assertTrue(choices.contains(lesson.answerCommandId()),
                    lesson.id() + " does not offer its own answer");
            assertFalse(lesson.decoyCommandIds().contains(lesson.answerCommandId()),
                    lesson.id() + " lists its answer as a decoy");
            assertEquals(choices, lesson.choices(),
                    lesson.id() + " reorders its tiles between frames");
        }
    }

    /** If the answer were always first, the course would be a clicking exercise. */
    @Test
    void theAnswerMovesAroundTheThreeSlots() {
        Set<Integer> positions = new HashSet<>();
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            positions.add(lesson.choices().indexOf(lesson.answerCommandId()));
        }
        assertTrue(positions.size() > 1,
                "the answer sits in the same slot in every lesson: " + positions);
    }

    /** Lesson ids are written into the player's config, so they are an interface, not a label. */
    @Test
    void lessonIdsAreUniqueAndResolvable() {
        List<TrainingLesson> lessons = TrainingCourse.lessons();
        assertEquals(lessons.size(), lessons.stream().map(TrainingLesson::id).distinct().count(),
                "two lessons share an id, so clearing one would clear the other");
        for (TrainingLesson lesson : lessons) {
            assertEquals(lesson, TrainingCourse.byId(lesson.id()).orElse(null));
            assertFalse(lesson.title().isBlank());
            assertFalse(lesson.about().isBlank());
            assertFalse(lesson.hint().isBlank());
        }
    }

    @Test
    void removingOriginalWorkCannotClearALesson() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            TaskGraph task = lesson.newAttempt();
            solve(lesson.id(), task);
            task.nodes.removeIf(node -> !node.id.equals("answer"));
            assertFalse(lesson.isSolvedBy(task), lesson.id());
        }
    }

    @Test
    void collectorMustRunOnBothOutcomesBeforeEnd() {
        TrainingLesson lesson = TrainingCourse.byId("collect").orElseThrow();
        TaskGraph task = lesson.newAttempt();
        solve(lesson.id(), task);
        task.nodeById("chop").onFailure = "end";
        assertFalse(lesson.isSolvedBy(task));
    }

    @Test
    void clockMustNotBypassCounterAndCounterMustCountThree() {
        TrainingLesson lesson = TrainingCourse.byId("count").orElseThrow();
        TaskGraph task = lesson.newAttempt();
        solve(lesson.id(), task);
        task.nodeById("pulse").alwaysTargets.add("loot");
        assertFalse(lesson.isSolvedBy(task));
        task.nodeById("pulse").alwaysTargets.remove("loot");
        task.nodeById("answer").params.put("count", "1");
        assertFalse(lesson.isSolvedBy(task));
    }

    @Test
    void scoutMustSeeLessFarThanWorker() {
        TrainingLesson lesson = TrainingCourse.byId("scout").orElseThrow();
        TaskGraph task = lesson.newAttempt();
        solve(lesson.id(), task);
        task.nodeById("answer").params.put("radius", "48");
        assertFalse(lesson.isSolvedBy(task));
    }

    // --- the fixes a player would make ---------------------------------------

    private static void solve(String lessonId, TaskGraph task) {
        switch (lessonId) {
            case "start" -> {
                TaskNode start = add(task, TaskNode.START_COMMAND, "answer");
                start.onSuccess = task.nodeById("chop").id;
            }
            case "tool" -> {
                TaskNode tool = add(task, "gettool", "answer");
                task.nodeById("start").onSuccess = tool.id;
                tool.onSuccess = task.nodeById("mine").id;
                tool.onFailure = task.nodeById("mine").id;
            }
            case "guard" -> {
                TaskNode guard = add(task, "self_preservation", "answer");
                guard.repeat = 0;
                task.nodeById("safety_clock").alwaysTargets.add(guard.id);
            }
            case "scout" -> {
                TaskNode explore = add(task, "explore", "answer");
                TaskNode loot = task.nodeById("loot");
                TaskNode chop = task.nodeById("chop");
                loot.onSuccess = explore.id;
                loot.onFailure = explore.id;
                explore.onSuccess = chop.id;
                explore.onFailure = chop.id;
            }
            case "collect" -> {
                TaskNode loot = add(task, "loot", "answer");
                task.nodeById("chop").onSuccess = loot.id;
                task.nodeById("chop").onFailure = loot.id;
                loot.onSuccess = "end";
                loot.onFailure = "end";
            }
            case "delay" -> {
                TaskNode timer = add(task, TaskNode.TIMER_COMMAND, "answer");
                timer.params.put("seconds", "2");
                task.nodeById("button").signalLinks.clear();
                task.nodeById("button").signalLinks.add(new TaskSignalLink(0, timer.id, -1));
                timer.signalLinks.add(new TaskSignalLink(0, "loot", -1));
            }
            case "manual" -> {
                TaskNode button = add(task, TaskNode.BUTTON_COMMAND, "answer");
                button.signalLinks.add(new TaskSignalLink(0, "loot", -1));
            }
            case "count" -> {
                TaskNode counter = add(task, TaskNode.COUNTER_COMMAND, "answer");
                counter.params.put("count", "3");
                task.nodeById("pulse").alwaysTargets.clear();
                task.nodeById("pulse").alwaysTargets.add(counter.id);
                counter.signalLinks.add(new TaskSignalLink(0, "loot", -1));
            }
            case "store" -> {
                TaskNode deposit = add(task, "deposit", "answer");
                deposit.params.put("filter", "Crops");
                task.nodeById("loot").onSuccess = deposit.id;
                task.nodeById("loot").onFailure = deposit.id;
                deposit.onSuccess = "end";
                deposit.onFailure = "end";
            }
            case "fanout" -> {
                TaskNode relay = add(task, TaskNode.SIGNAL_RELAY_COMMAND, "answer");
                relay.signalInputCount = 1;
                relay.signalOutputCount = 2;
                task.nodeById("pulse").alwaysTargets.add(relay.id);
                relay.signalLinks.add(new TaskSignalLink(0, task.nodeById("sweep").id, -1));
                relay.signalLinks.add(new TaskSignalLink(1, task.nodeById("top_up").id, -1));
            }
            default -> throw new AssertionError("no written solution for lesson '" + lessonId
                    + "'; add one here when adding a lesson");
        }
    }

    private static TaskNode add(TaskGraph task, String commandId, String id) {
        TaskNode node = new TaskNode(commandId);
        node.id = id;
        node.editorX = 24;
        node.editorY = 300;
        task.nodes.add(node);
        assertNotNull(task.nodeById(id));
        return node;
    }
}
