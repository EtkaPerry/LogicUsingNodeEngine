package com.etka.lune.task;

import com.etka.lune.training.TrainingPreview;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Success and Fail drawn as one Done pin, for a card that carries on the same way however it ends.
 *
 * <p>Joining is a way of drawing a card, not a way of running one: a joined card keeps both of its
 * edges, pointed at the same card, which is how "either way" was always wired. So nearly all of
 * this is about keeping the two edges together while the pin is joined - and never letting the
 * pin hide an edge that goes somewhere else.</p>
 */
class JoinedOutcomesTest {

    @Test
    void everyCardStartsWithSeparatePins() {
        TaskNode mine = new TaskNode("mine");

        assertFalse(mine.outcomesJoined);
        assertFalse(mine.showsDone());
    }

    @Test
    void aTaskWrittenBeforeDoneExistedKeepsItsTwoPins() {
        // Both wires to one card is how "either way" has always been drawn, and it stays drawn
        // that way until the player asks for Done.
        TaskNode old = new Gson().fromJson(
                "{\"commandId\":\"chop\",\"onSuccess\":\"loot\",\"onFailure\":\"loot\"}",
                TaskNode.class);

        assertFalse(old.showsDone());
    }

    @Test
    void joiningLendsTheOnlyWireToTheOtherPin() {
        TaskGraph task = new TaskGraph("Either way");
        TaskNode chop = card(task, "chop");
        TaskNode loot = card(task, "loot");
        chop.onSuccess = loot.id;

        assertEquals(TaskWiring.JoinResult.JOINED, TaskWiring.joinOutcomes(task, chop));

        assertTrue(chop.showsDone());
        assertEquals(loot.id, chop.onSuccess);
        assertEquals(loot.id, chop.onFailure, "a failure now goes where the Done pin leads");
        assertEquals(List.of(loot), TaskWiring.outgoing(task, chop));
    }

    @Test
    void aFailOnlyCardTakesItsCableShapeIntoDone() {
        TaskGraph task = new TaskGraph("Fail only");
        TaskNode mine = card(task, "mine");
        TaskNode eat = card(task, "eat");
        mine.onFailure = eat.id;
        TaskCableRoute shape = new TaskCableRoute(new TaskCableAnchor(40, 200));
        task.cableAnchors.put(TaskCableAnchor.key("failure", mine.id, eat.id), shape);

        assertEquals(TaskWiring.JoinResult.JOINED, TaskWiring.joinOutcomes(task, mine));

        assertEquals(eat.id, mine.onSuccess);
        assertEquals(eat.id, mine.onFailure);
        // The Done cable is drawn under the Success cable's key, so the shape moves with it.
        assertSame(shape, task.cableAnchors.get(TaskCableAnchor.key("success", mine.id, eat.id)));
        assertFalse(task.cableAnchors.containsKey(TaskCableAnchor.key("failure", mine.id, eat.id)));
    }

    @Test
    void twoUnwiredPinsJoinAsTheyAre() {
        TaskGraph task = new TaskGraph("Unwired");
        TaskNode walk = card(task, "walk");

        assertEquals(TaskWiring.JoinResult.JOINED, TaskWiring.joinOutcomes(task, walk));

        assertTrue(walk.showsDone());
        assertNull(walk.onSuccess);
        assertNull(walk.onFailure);
    }

    @Test
    void pinsLeadingToDifferentCardsAreNeverJoinedByDroppingOne() {
        TaskGraph task = new TaskGraph("Branch");
        TaskNode check = card(task, "check_item");
        TaskNode yes = card(task, "craft");
        TaskNode no = card(task, "mine");
        check.onSuccess = yes.id;
        check.onFailure = no.id;
        // Left on by a hand-edited file: refusing also switches it off, so the card cannot join
        // itself later the moment the two wires happen to meet.
        check.outcomesJoined = true;

        assertEquals(TaskWiring.JoinResult.LEAD_APART, TaskWiring.joinOutcomes(task, check));

        assertFalse(check.outcomesJoined);
        assertEquals(yes.id, check.onSuccess);
        assertEquals(no.id, check.onFailure);
    }

    @Test
    void cardsWithoutSuccessAndFailHaveNothingToJoin() {
        TaskGraph task = new TaskGraph("Not jobs");
        for (String command : List.of(TaskNode.START_COMMAND, TaskNode.ALWAYS_COMMAND,
                TaskNode.PULSE_COMMAND, TaskNode.SIGNAL_RELAY_COMMAND, TaskNode.TIMER_COMMAND,
                TaskNode.COUNTER_COMMAND, TaskNode.END_COMMAND, TaskNode.OBSERVER_COMMAND,
                TaskNode.BUTTON_COMMAND)) {
            TaskNode node = card(task, command);

            assertEquals(TaskWiring.JoinResult.NO_OUTCOMES, TaskWiring.joinOutcomes(task, node),
                    command);
            assertFalse(node.showsDone(), command);
        }
    }

    @Test
    void splittingLeavesBothWiresWhereDoneLed() {
        TaskGraph task = new TaskGraph("Split");
        TaskNode chop = card(task, "chop");
        TaskNode loot = card(task, "loot");
        chop.onSuccess = loot.id;
        TaskWiring.joinOutcomes(task, chop);

        assertTrue(TaskWiring.splitOutcomes(chop));

        assertFalse(chop.showsDone());
        assertEquals(loot.id, chop.onSuccess);
        assertEquals(loot.id, chop.onFailure, "nothing runs differently until a pin is rewired");
        assertFalse(TaskWiring.splitOutcomes(chop));
    }

    @Test
    void wiringDoneWritesBothEdgesAndDropsTheOldCableShapes() {
        TaskGraph task = new TaskGraph("Rewire");
        TaskNode chop = card(task, "chop");
        TaskNode loot = card(task, "loot");
        TaskNode relay = card(task, TaskNode.SIGNAL_RELAY_COMMAND);
        relay.signalInputCount = 3;
        chop.onSuccess = loot.id;
        TaskWiring.joinOutcomes(task, chop);
        task.cableAnchors.put(TaskCableAnchor.key("success", chop.id, loot.id),
                new TaskCableRoute(new TaskCableAnchor(1, 2)));

        assertTrue(TaskWiring.wireDone(task, chop, relay, 2));

        assertEquals(relay.id, chop.onSuccess);
        assertEquals(relay.id, chop.onFailure);
        assertEquals(2, chop.successInputPort);
        assertEquals(2, chop.failureInputPort, "both edges land on the input it was dropped on");
        assertTrue(chop.showsDone());
        assertTrue(task.cableAnchors.isEmpty(), "the old cable's shape belonged to the old cable");
        assertFalse(TaskWiring.wireDone(task, chop, relay, 2), "the same drop again is no change");
    }

    @Test
    void cuttingDoneCutsBothEdges() {
        TaskGraph task = new TaskGraph("Cut");
        TaskNode chop = card(task, "chop");
        TaskNode loot = card(task, "loot");
        chop.onSuccess = loot.id;
        TaskWiring.joinOutcomes(task, chop);
        task.cableAnchors.put(TaskCableAnchor.key("success", chop.id, loot.id),
                new TaskCableRoute(new TaskCableAnchor(1, 2)));

        assertTrue(TaskWiring.cutDone(task, chop));

        assertNull(chop.onSuccess);
        assertNull(chop.onFailure, "a failure must not travel down a cable the player just cut");
        assertTrue(chop.showsDone(), "the cable goes, the pin stays");
        assertTrue(task.cableAnchors.isEmpty());
        assertFalse(TaskWiring.cutDone(task, chop));
    }

    @Test
    void aStepInsertedAfterAJoinedCardGoesOnTheDoneWire() {
        TaskGraph task = new TaskGraph("Deposit");
        TaskNode chop = card(task, "chop");
        TaskNode next = card(task, "walk");
        chop.onSuccess = next.id;
        TaskWiring.joinOutcomes(task, chop);

        TaskNode deposit = TaskWiring.insertAfter(task, chop, "deposit",
                Map.of("optional", "true"));

        assertNotNull(deposit);
        assertEquals(deposit.id, chop.onSuccess);
        assertEquals(deposit.id, chop.onFailure);
        assertTrue(chop.showsDone());
        assertEquals(next.id, deposit.onSuccess);
        assertEquals(next.id, deposit.onFailure);
    }

    @Test
    void aSplitCardKeepsItsFailWireWhenAStepIsInsertedAfterIt() {
        TaskGraph task = new TaskGraph("Split deposit");
        TaskNode chop = card(task, "chop");
        TaskNode next = card(task, "walk");
        TaskNode rescue = card(task, "eat");
        chop.onSuccess = next.id;
        chop.onFailure = rescue.id;

        TaskNode deposit = TaskWiring.insertAfter(task, chop, "deposit", Map.of());

        assertNotNull(deposit);
        assertEquals(deposit.id, chop.onSuccess);
        assertEquals(rescue.id, chop.onFailure);
    }

    @Test
    void aJoinedCardWhoseEdgesDisagreeIsDrawnSplitAndLoadsSplit() {
        TaskGraph task = new TaskGraph("Hand edited");
        TaskNode check = card(task, "check_item");
        check.onSuccess = card(task, "craft").id;
        check.onFailure = card(task, "mine").id;
        check.outcomesJoined = true;

        assertFalse(check.showsDone(), "one Done pin would hide the Fail wire");

        TaskWiring.normalizeOutcomes(check);

        assertFalse(check.outcomesJoined);
    }

    @Test
    void loadingKeepsAJoinedCardJoinedAndItsRelayInputsTogether() {
        TaskNode chop = new TaskNode("chop");
        chop.onSuccess = "relay";
        chop.onFailure = "relay";
        chop.successInputPort = 1;
        chop.failureInputPort = 0;
        chop.outcomesJoined = true;

        TaskWiring.normalizeOutcomes(chop);

        assertTrue(chop.showsDone());
        assertEquals(1, chop.failureInputPort);
    }

    @Test
    void aPulseCardLoadedWithTheSwitchOnLosesIt() {
        TaskNode timer = new TaskNode(TaskNode.TIMER_COMMAND);
        timer.outcomesJoined = true;

        TaskWiring.normalizeOutcomes(timer);

        assertFalse(timer.outcomesJoined);
    }

    @Test
    void theJoinTravelsWithCopiesUndoAndSharedJson() {
        TaskGraph task = new TaskGraph("Travels");
        TaskNode chop = card(task, "chop");
        TaskNode loot = card(task, "loot");
        chop.onSuccess = loot.id;
        TaskWiring.joinOutcomes(task, chop);

        assertTrue(chop.copy().outcomesJoined);
        assertTrue(TaskWiring.copy(task).nodes.get(0).showsDone());
        assertTrue(new Gson().fromJson(new Gson().toJson(task), TaskGraph.class)
                .nodes.get(0).showsDone());

        TaskGraph before = TaskWiring.copy(task);
        TaskWiring.splitOutcomes(chop);
        TaskWiring.restore(task, before);

        assertTrue(chop.showsDone(), "undo puts the Done pin back on the same card");
    }

    @Test
    void doneTakesTheSuccessRowAndWhileMovesUpIntoFails() {
        TaskNode split = new TaskNode("chop");
        split.editorX = 0;
        split.editorY = 100;
        TaskNode joined = split.copy();
        joined.outcomesJoined = true;

        assertEquals(100 + TaskCanvas.FAILURE_OFFSET_Y, TaskCanvas.failureY(split));
        assertEquals(100 + TaskCanvas.WHILE_OFFSET_Y, TaskCanvas.whileY(split));
        assertEquals(TaskCanvas.successY(joined), TaskCanvas.failureY(joined),
                "a joined card's failure leaves through Done");
        assertEquals(100 + TaskCanvas.FAILURE_OFFSET_Y, TaskCanvas.whileY(joined));
    }

    @Test
    void theWiringPreviewReachesTheDoneCardWhicheverWayTheCardEnds() {
        TaskGraph task = new TaskGraph("Preview");
        TaskNode start = card(task, TaskNode.START_COMMAND);
        TaskNode chop = card(task, "chop");
        TaskNode loot = card(task, "loot");
        start.onSuccess = chop.id;
        chop.onSuccess = loot.id;
        TaskWiring.joinOutcomes(task, chop);

        for (boolean failure : new boolean[]{false, true}) {
            assertTrue(TrainingPreview.trace(task, failure).nodes().containsKey(loot.id),
                    failure ? "on Fail" : "on Success");
        }
    }

    private static TaskNode card(TaskGraph task, String command) {
        TaskNode node = new TaskNode(command);
        task.nodes.add(node);
        return node;
    }
}
