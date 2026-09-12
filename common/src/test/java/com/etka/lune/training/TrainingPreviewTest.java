package com.etka.lune.training;

import com.etka.lune.task.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrainingPreviewTest {
    @Test
    void counterHoldsTwoPulsesAndForwardsTheThird() {
        TaskGraph task = TrainingCourse.byId("count").orElseThrow().newAttempt();
        TaskNode counter = new TaskNode(TaskNode.COUNTER_COMMAND);
        counter.signalLinks.add(new TaskSignalLink(0, "loot", -1));
        task.nodes.add(counter);
        task.nodeById("pulse").alwaysTargets.clear();
        task.nodeById("pulse").alwaysTargets.add(counter.id);
        var counts = new java.util.HashMap<String, Integer>();
        assertFalse(TrainingPreview.trace(task, false, counts).nodes().containsKey("loot"));
        assertFalse(TrainingPreview.trace(task, false, counts).nodes().containsKey("loot"));
        assertTrue(TrainingPreview.trace(task, false, counts).nodes().containsKey("loot"));
        assertFalse(TrainingPreview.trace(task, false, counts).nodes().containsKey("loot"));
    }

    @Test
    void timerHoldsItsLightBeforeSendingOutput() {
        TaskGraph task = TrainingCourse.byId("delay").orElseThrow().newAttempt();
        TaskNode timer = new TaskNode(TaskNode.TIMER_COMMAND);
        timer.params.put("seconds", "5");
        timer.signalLinks.add(new TaskSignalLink(0, "loot", -1));
        task.nodes.add(timer);
        task.nodeById("button").signalLinks.clear();
        task.nodeById("button").signalLinks.add(new TaskSignalLink(0, timer.id, -1));
        TrainingPreview preview = TrainingPreview.trace(task, false);
        assertTrue(preview.nodeLit(timer, 2 * TrainingPreview.STAGE_MILLIS));
        assertEquals(-1, preview.wireProgress(timer, TaskPower.SIGNAL, 0,
                task.nodeById("loot"), 2 * TrainingPreview.STAGE_MILLIS));
        assertEquals(0, preview.wireProgress(timer, TaskPower.SIGNAL, 0,
                task.nodeById("loot"), 3 * TrainingPreview.STAGE_MILLIS));
    }

    @Test
    void followsChosenOutcomeAndFinishesWithAllLightsOff() {
        TaskGraph task = TrainingCourse.byId("collect").orElseThrow().newAttempt();
        TaskNode chop = task.nodeById("chop");
        TaskNode end = task.nodeById("end");
        TaskNode alternate = new TaskNode("loot");
        task.nodes.add(alternate);
        chop.onFailure = alternate.id;
        TrainingPreview success = TrainingPreview.trace(task, false);
        TrainingPreview failure = TrainingPreview.trace(task, true);
        assertTrue(success.nodes().containsKey(end.id));
        assertFalse(success.nodes().containsKey(alternate.id));
        assertFalse(failure.nodes().containsKey(end.id));
        assertTrue(failure.nodes().containsKey(alternate.id));
        assertEquals(0.5, success.wireProgress(chop, TaskPower.SUCCESS, 0, end, 1275));
        long finished = success.stages() * TrainingPreview.STAGE_MILLIS;
        assertTrue(success.finished(finished));
        for (TaskNode node : task.nodes) assertFalse(success.nodeLit(node, finished));
        assertEquals(-1, success.wireProgress(chop, TaskPower.SUCCESS, 0, end, finished));
    }

    @Test
    void loopsCloseOnceAndSourcesDoNotRepeat() {
        TaskGraph task = TrainingCourse.byId("guard").orElseThrow().newAttempt();
        TrainingPreview preview = TrainingPreview.trace(task, false);
        assertEquals(3, preview.wires().size()); // START -> Chop -> Loot -> Chop.
        assertTrue(preview.stages() < 10);
    }

    @Test
    void disconnectedCardsAndImplicitFallthroughAreNotShownAsWires() {
        TaskGraph task = TrainingCourse.byId("start").orElseThrow().newAttempt();
        assertTrue(TrainingPreview.trace(task, false).nodes().isEmpty());
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        start.onSuccess = "chop";
        task.nodes.add(start);
        task.nodeById("chop").onSuccess = null;
        TrainingPreview preview = TrainingPreview.trace(task, false);
        assertFalse(preview.nodes().containsKey("loot"));
    }

    @Test
    void relayLightsBothBranchesAndPreviewDoesNotMutateGraph() {
        TaskGraph task = TrainingCourse.byId("fanout").orElseThrow().newAttempt();
        TaskNode relay = new TaskNode(TaskNode.SIGNAL_RELAY_COMMAND);
        relay.signalOutputCount = 2;
        relay.signalLinks.add(new TaskSignalLink(0, "sweep", -1));
        relay.signalLinks.add(new TaskSignalLink(1, "top_up", -1));
        task.nodes.add(relay);
        task.nodeById("pulse").alwaysTargets.add(relay.id);
        TrainingPreview preview = TrainingPreview.trace(task, false);
        assertEquals(preview.nodes().get("sweep"), preview.nodes().get("top_up"));
        assertTrue(preview.nodes().containsKey("sweep_end"));
        assertTrue(preview.nodes().containsKey("top_up_end"));
        assertEquals(2, relay.signalLinks.size());
        assertEquals(6, task.nodes.size());
    }
}
