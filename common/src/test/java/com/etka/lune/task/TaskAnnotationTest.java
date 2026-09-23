package com.etka.lune.task;

import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sticky notes and card groups: the two marks on the canvas that never run. */
class TaskAnnotationTest {

    @AfterEach
    void restoreLanguage() {
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    private static TaskNode card(TaskGraph task, int x, int y) {
        TaskNode node = new TaskNode("mine");
        node.editorX = x;
        node.editorY = y;
        task.nodes.add(node);
        return node;
    }

    private static int fixedHeight(TaskNode ignored) {
        return TaskCanvas.CARD_HEIGHT;
    }

    @Test
    void aGroupMeasuresItselfAroundItsCards() {
        TaskGraph task = new TaskGraph("Frames");
        TaskNode left = card(task, 100, 100);
        TaskNode right = card(task, 300, 180);
        TaskGroup group = TaskGroup.group(task, List.of(left, right), "Smelting");

        assertTrue(group.fit(task, TaskAnnotationTest::fixedHeight));

        assertEquals(100 - TaskGroup.PADDING, group.x);
        assertEquals(100 - TaskGroup.PADDING, group.y);
        assertEquals(300 + TaskCanvas.CARD_WIDTH + TaskGroup.PADDING, group.right());
        assertEquals(180 + TaskCanvas.CARD_HEIGHT + TaskGroup.PADDING, group.bottom());
    }

    @Test
    void aCardBelongsToOneGroupAtATime() {
        TaskGraph task = new TaskGraph("Frames");
        TaskNode shared = card(task, 0, 0);
        TaskNode other = card(task, 200, 0);
        TaskGroup first = TaskGroup.group(task, List.of(shared, other), "First");

        TaskGroup second = TaskGroup.group(task, List.of(shared), "Second");

        assertSame(second, TaskGroup.holding(task, shared));
        assertFalse(first.holds(shared));
        assertTrue(first.holds(other));
        assertEquals(2, task.groups.size());
    }

    @Test
    void aGroupLeftWithNothingInItIsDropped() {
        TaskGraph task = new TaskGraph("Frames");
        TaskNode only = card(task, 0, 0);
        TaskGroup first = TaskGroup.group(task, List.of(only), "First");

        TaskGroup.group(task, List.of(only), "Second");

        assertFalse(task.groups.contains(first));
        assertEquals(1, task.groups.size());
    }

    @Test
    void draggingAGroupCarriesItsCards() {
        TaskGraph task = new TaskGraph("Frames");
        TaskNode inside = card(task, 40, 60);
        TaskNode outside = card(task, 400, 60);
        TaskGroup group = TaskGroup.group(task, List.of(inside), "Moving");
        group.fit(task, TaskAnnotationTest::fixedHeight);

        group.moveBy(task, 25, -10);

        assertEquals(65, inside.editorX);
        assertEquals(50, inside.editorY);
        assertEquals(400, outside.editorX, "a card outside the group must not move with it");
    }

    @Test
    void aCollapsedGroupKeepsThePlaceItsCardsWillComeBackTo() {
        TaskGraph task = new TaskGraph("Frames");
        TaskNode inside = card(task, 120, 240);
        TaskGroup group = TaskGroup.group(task, List.of(inside), "Closed");
        group.fit(task, TaskAnnotationTest::fixedHeight);
        int openX = group.x;
        int openY = group.y;

        group.collapsed = true;
        group.fit(task, TaskAnnotationTest::fixedHeight);

        assertEquals(openX, group.x);
        assertEquals(openY, group.y);
        assertEquals(0, group.bottom() - group.y, "a closed group is its title bar and nothing else");
        assertTrue(TaskGroup.isHidden(task, inside));
    }

    @Test
    void deletingACardTakesItOutOfItsGroupAndDropsAnEmptyOne() {
        TaskGraph task = new TaskGraph("Frames");
        TaskNode kept = card(task, 0, 0);
        TaskNode doomed = card(task, 200, 0);
        TaskGroup both = TaskGroup.group(task, List.of(kept, doomed), "Both");
        TaskGroup lonely = TaskGroup.group(task, List.of(card(task, 400, 0)), "Lonely");

        task.nodes.removeIf(node -> node == doomed || lonely.holds(node));
        TaskGroup.prune(task);

        assertTrue(task.groups.contains(both));
        assertFalse(both.holds(doomed));
        assertFalse(task.groups.contains(lonely));
    }

    @Test
    void ungroupingRemovesOnlyTheGroupsHoldingTheGivenCards() {
        TaskGraph task = new TaskGraph("Frames");
        TaskNode mine = card(task, 0, 0);
        TaskNode theirs = card(task, 400, 0);
        TaskGroup ours = TaskGroup.group(task, List.of(mine), "Ours");
        TaskGroup untouched = TaskGroup.group(task, List.of(theirs), "Theirs");

        assertEquals(1, TaskGroup.ungroup(task, List.of(mine)));

        assertFalse(task.groups.contains(ours));
        assertTrue(task.groups.contains(untouched));
        assertNull(TaskGroup.holding(task, mine));
    }

    @Test
    void aNoteCannotBeShrunkIntoNothing() {
        TaskNote note = new TaskNote(10, 10);
        note.width = 4;
        note.height = 1;

        note.clampSize();

        assertEquals(TaskNote.MIN_WIDTH, note.width);
        assertEquals(TaskNote.MIN_HEIGHT, note.height);
        assertTrue(note.contains(10, 10));
        assertFalse(note.contains(note.right(), note.bottom()));
        assertTrue(note.inResizeCorner(note.right() - 1, note.bottom() - 1));
    }

    @Test
    void aSnapshotCarriesNotesGroupsAndBreakpoints() {
        TaskGraph task = new TaskGraph("Snapshot");
        TaskNode watched = card(task, 0, 0);
        watched.breakpoint = true;
        TaskGroup.group(task, List.of(watched), "Kept");
        TaskNote note = new TaskNote(5, 5);
        note.text = "why this is wired backwards";
        task.notes.add(note);

        TaskGraph copy = TaskWiring.copy(task);

        assertEquals(1, copy.notes.size());
        assertEquals("why this is wired backwards", copy.notes.get(0).text);
        assertEquals(1, copy.groups.size());
        assertEquals("Kept", copy.groups.get(0).title);
        assertTrue(copy.nodes.get(0).breakpoint);
    }

    @Test
    void undoingRestoresNotesAndGroupsAsWellAsCards() {
        TaskGraph task = new TaskGraph("Undo");
        TaskNode kept = card(task, 0, 0);
        kept.breakpoint = true;
        TaskGroup.group(task, List.of(kept), "Before");
        TaskGraph snapshot = TaskWiring.copy(task);

        task.groups.clear();
        task.notes.add(new TaskNote(0, 0));
        kept.breakpoint = false;
        TaskWiring.restore(task, snapshot);

        assertEquals(1, task.groups.size());
        assertEquals("Before", task.groups.get(0).title);
        assertTrue(task.notes.isEmpty());
        assertTrue(task.nodes.get(0).breakpoint);
        assertSame(kept, task.nodes.get(0), "a running task must keep its live node objects");
    }

    /**
     * A starter job's note is drawn in the player's language, and stops being a starter note the
     * moment they edit it - keeping the words they were looking at, not the English underneath.
     */
    @Test
    void aStarterNoteFollowsTheLanguageUntilThePlayerEditsIt() {
        TaskNote note = starterNote();
        Lang.select("tr_tr");
        String turkish = note.displayText();
        assertNotEquals(note.text, turkish, "the note should be drawn in Turkish");

        note.adopt();

        assertNull(note.seededId);
        assertEquals(turkish, note.text, "editing starts from the words on screen");
        Lang.select("en_us");
        assertEquals(turkish, note.displayText(), "once edited it is theirs in every language");
    }

    @Test
    void aNoteThePlayerWroteIsDrawnAsWritten() {
        TaskNote note = new TaskNote(0, 0);
        note.text = "kendi notum";
        Lang.select("en_us");
        assertEquals("kendi notum", note.displayText());
        note.adopt();
        assertEquals("kendi notum", note.text, "adopting a note that is already theirs changes nothing");
    }

    @Test
    void aCopiedStarterNoteIsStillTranslated() {
        TaskNote copy = starterNote().copy();
        Lang.select("tr_tr");
        assertNotEquals(copy.text, copy.displayText());
    }

    @Test
    void aStarterFrameTitleFollowsTheLanguageUntilRenamed() {
        TaskGroup frame = new TaskGroup("Safety");
        frame.seededId = "safety";
        Lang.select("tr_tr");
        String turkish = frame.displayTitle();
        assertNotEquals("Safety", turkish);
        assertEquals(turkish, frame.copy().displayTitle());

        frame.adopt();

        assertNull(frame.seededId);
        assertEquals(turkish, frame.title);
    }

    private static TaskNote starterNote() {
        TaskNote note = DefaultTasks.create().getFirst().notes.getFirst().copy();
        assertEquals("chop_wood.intro", note.seededId);
        return note;
    }

    @Test
    void aFreshCardFromATemplateDoesNotInheritABreakpoint() {
        TaskNode template = new TaskNode("mine");
        template.breakpoint = true;

        TaskNode fresh = template.copy();

        assertFalse(fresh.breakpoint);
        assertFalse(fresh.id.equals(template.id));
    }
}
