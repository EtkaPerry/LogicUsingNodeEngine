package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the find bar answers with.
 *
 * <p>The name a card is asked for by is translated, so the checks here pin down the two halves
 * that are not: the command id underneath it, and the parameters the player typed themselves.</p>
 */
class TaskSearchTest {

    /** Stands in for the canvas's own translated card title. */
    private static String nameOf(TaskNode node) {
        return switch (node.commandId) {
            case "mine" -> "Mine";
            case "goto" -> "Go to";
            default -> node.commandId;
        };
    }

    private static List<TaskSearch.Hit> find(TaskGraph task, String query) {
        return TaskSearch.find(task, query, TaskSearchTest::nameOf,
                node -> TaskCanvas.CARD_HEIGHT);
    }

    private static TaskNode card(TaskGraph task, String commandId, int x, int y) {
        TaskNode node = new TaskNode(commandId);
        node.editorX = x;
        node.editorY = y;
        task.nodes.add(node);
        return node;
    }

    @Test
    void findsACardByItsTitle() {
        TaskGraph task = new TaskGraph("Search");
        TaskNode mine = card(task, "mine", 0, 0);

        List<TaskSearch.Hit> hits = find(task, "min");

        assertEquals(1, hits.size());
        assertEquals(mine.id, hits.get(0).id());
        assertEquals(TaskSearch.Kind.CARD, hits.get(0).kind());
    }

    @Test
    void findsACardByItsCommandIdWhenTheTitleIsInAnotherLanguage() {
        TaskGraph task = new TaskGraph("Search");
        TaskNode walk = card(task, "goto", 0, 0);

        // "goto" is never drawn on the card; a player who knows the id must still be able to
        // reach it, and a translated canvas must not answer differently from an English one.
        List<TaskSearch.Hit> hits = TaskSearch.find(task, "goto",
                node -> "Şuraya git", node -> TaskCanvas.CARD_HEIGHT);

        assertEquals(1, hits.size());
        assertEquals(walk.id, hits.get(0).id());
    }

    @Test
    void findsACardByWhatWasTypedIntoIt() {
        TaskGraph task = new TaskGraph("Search");
        TaskNode mine = card(task, "mine", 0, 0);
        mine.params.put("targets", "minecraft:deepslate_diamond_ore");

        List<TaskSearch.Hit> hits = find(task, "diamond");

        assertEquals(1, hits.size());
        assertEquals(mine.id, hits.get(0).id());
        assertTrue(hits.get(0).matched().contains("deepslate_diamond_ore"));
    }

    @Test
    void findsNotesAndGroupsBesideCards() {
        TaskGraph task = new TaskGraph("Search");
        card(task, "mine", 0, 400);
        TaskNote note = new TaskNote(0, 0);
        note.text = "the iron pass is flaky here";
        task.notes.add(note);
        TaskGroup group = new TaskGroup("iron section");
        group.members.add("whatever");
        group.y = 200;
        task.groups.add(group);

        List<TaskSearch.Hit> hits = find(task, "iron");

        assertEquals(List.of(TaskSearch.Kind.NOTE, TaskSearch.Kind.GROUP),
                hits.stream().map(TaskSearch.Hit::kind).toList());
        assertEquals(note.id, hits.get(0).id());
        assertEquals(group.id, hits.get(1).id());
    }

    @Test
    void resultsComeBackInReadingOrderRatherThanListOrder() {
        TaskGraph task = new TaskGraph("Search");
        TaskNode low = card(task, "mine", 0, 500);
        TaskNode high = card(task, "mine", 0, 20);
        TaskNode middleRight = card(task, "mine", 400, 260);
        TaskNode middleLeft = card(task, "mine", 40, 260);

        List<String> order = find(task, "mine").stream().map(TaskSearch.Hit::id).toList();

        assertEquals(List.of(high.id, middleLeft.id, middleRight.id, low.id), order);
    }

    @Test
    void aBlankQueryAnswersWithNothingRatherThanWithEverything() {
        TaskGraph task = new TaskGraph("Search");
        card(task, "mine", 0, 0);

        assertTrue(find(task, "").isEmpty());
        assertTrue(find(task, "   ").isEmpty());
        assertTrue(find(task, null).isEmpty());
    }

    @Test
    void matchingIgnoresCase() {
        TaskGraph task = new TaskGraph("Search");
        card(task, "mine", 0, 0);

        assertEquals(1, find(task, "MINE").size());
    }
}
