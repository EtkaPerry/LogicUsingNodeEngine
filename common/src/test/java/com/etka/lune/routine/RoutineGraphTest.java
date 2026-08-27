package com.etka.lune.routine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoutineGraphTest {

    @Test
    void insertsBeforeWithoutBreakingFallthroughOrExplicitEntries() {
        Routine routine = new Routine("Prepare");
        RoutineNode first = new RoutineNode("walk");
        RoutineNode alternate = new RoutineNode("find");
        RoutineNode target = new RoutineNode("mine");
        alternate.onFailure = target.id;
        routine.nodes.add(first);
        routine.nodes.add(alternate);
        routine.nodes.add(target);

        RoutineNode inserted = RoutineGraph.insertBefore(routine, target, "eat",
                Map.of("minimum_food", "18"));

        assertNotNull(inserted);
        assertEquals(inserted, routine.nodes.get(2));
        assertEquals(target.id, inserted.onSuccess);
        assertEquals(target.id, inserted.onFailure);
        assertEquals(inserted.id, alternate.onFailure);
        assertEquals(inserted, RoutineGraph.outgoing(routine, alternate).get(0));
    }

    @Test
    void insertsAfterAndPreservesFormerSuccessDestination() {
        Routine routine = new Routine("Deposit");
        RoutineNode work = new RoutineNode("chop");
        RoutineNode next = new RoutineNode("walk");
        routine.nodes.add(work);
        routine.nodes.add(next);

        RoutineNode inserted = RoutineGraph.insertAfter(routine, work, "deposit",
                Map.of("optional", "true"));

        assertNotNull(inserted);
        assertEquals(inserted.id, work.onSuccess);
        assertEquals(next.id, inserted.onSuccess);
        assertEquals(next.id, inserted.onFailure);
        assertEquals(next, routine.nodes.get(2));
    }

    @Test
    void refusesToEditCyclesAndForeverCards() {
        Routine routine = new Routine("Loop");
        RoutineNode loop = new RoutineNode("mine");
        loop.onSuccess = loop.id;
        routine.nodes.add(loop);

        assertFalse(RoutineGraph.canInsertBefore(routine, loop));
        assertNull(RoutineGraph.insertBefore(routine, loop, "eat", Map.of()));

        loop.onSuccess = null;
        loop.repeat = 0;
        assertFalse(RoutineGraph.canInsertAfter(routine, loop));
    }

    @Test
    void restoreRemovesAddedNodesButKeepsOriginalObjectsAlive() {
        Routine routine = new Routine("Undo");
        RoutineNode work = new RoutineNode("chop");
        work.editorX = 100;
        work.editorY = 40;
        routine.nodes.add(work);
        Routine snapshot = RoutineGraph.copy(routine);

        RoutineNode added = RoutineGraph.insertAfter(routine, work, "deposit", Map.of());
        work.params.put("limit", "64");
        assertNotNull(added);

        assertTrue(RoutineGraph.restore(routine, snapshot));
        assertEquals(1, routine.nodes.size());
        assertSame(work, routine.nodes.get(0));
        assertNull(work.onSuccess);
        assertFalse(work.params.containsKey("limit"));
        assertEquals(100, work.editorX);
    }

    @Test
    void explicitStartChoosesItsSuccessTargetInsteadOfListOrder() {
        Routine routine = new Routine("Explicit entry");
        RoutineNode start = new RoutineNode(RoutineNode.START_COMMAND);
        RoutineNode skipped = new RoutineNode("eat");
        RoutineNode first = new RoutineNode("mine");
        start.onSuccess = first.id;
        routine.nodes.add(start);
        routine.nodes.add(skipped);
        routine.nodes.add(first);

        assertEquals(first, RoutineGraph.nextSequentialNode(routine, -1));
        assertEquals(List.of(first), RoutineGraph.outgoing(routine, start));
    }

    @Test
    void explicitStartWithoutAnEdgeDoesNotGuessTheFirstCard() {
        Routine routine = new Routine("Unwired entry");
        routine.nodes.add(new RoutineNode(RoutineNode.START_COMMAND));
        routine.nodes.add(new RoutineNode("mine"));

        assertNull(RoutineGraph.nextSequentialNode(routine, -1));
    }

    @Test
    void addingStartPreservesTheExistingEntryPoint() {
        Routine routine = new Routine("Legacy route");
        RoutineNode work = new RoutineNode("mine");
        routine.nodes.add(work);

        RoutineNode start = RoutineGraph.addExplicitStart(routine);

        assertNotNull(start);
        assertEquals(start, routine.nodes.get(0));
        assertEquals(work.id, start.onSuccess);
        assertEquals(work, RoutineGraph.nextSequentialNode(routine, -1));
    }

    @Test
    void alwaysServesAsTheImplicitEntryPoint() {
        Routine routine = new Routine("Protected route");
        RoutineNode always = new RoutineNode(RoutineNode.ALWAYS_COMMAND);
        RoutineNode guard = new RoutineNode(RoutineSafety.COMMAND_ID);
        RoutineNode work = new RoutineNode("mine");
        RoutineNode backgroundWork = new RoutineNode("chop");
        always.alwaysTargets.add(guard.id);
        always.alwaysTargets.add(backgroundWork.id);
        routine.nodes.add(always);
        routine.nodes.add(guard);
        routine.nodes.add(work);
        routine.nodes.add(backgroundWork);

        assertTrue(RoutineGraph.hasAlwaysNode(routine));
        assertNull(RoutineGraph.addExplicitStart(routine));
        assertNull(RoutineGraph.explicitStart(routine));
        assertEquals(work, RoutineGraph.nextSequentialNode(routine, -1));
        assertEquals(2, always.alwaysTargets.size());
    }

    @Test
    void copiesAndDescribesAlwaysPulseFrequency() {
        Routine routine = new Routine("Pulse");
        RoutineNode always = new RoutineNode(RoutineNode.ALWAYS_COMMAND);
        always.alwaysIntervalSeconds = 30;
        routine.nodes.add(always);

        Routine copy = RoutineGraph.copy(routine);

        assertEquals(30, copy.nodes.get(0).alwaysIntervalSeconds);
        assertEquals(600, copy.nodes.get(0).alwaysIntervalTicks());
        assertEquals("every 30 seconds", copy.nodes.get(0).describeAlwaysInterval());
    }

    @Test
    void copiesConfigurableRelayPortsAndPulseWires() {
        Routine routine = new Routine("Relay");
        RoutineNode relay = new RoutineNode(RoutineNode.SIGNAL_RELAY_COMMAND);
        relay.signalInputCount = 3;
        relay.signalOutputCount = 2;
        relay.signalLinks.add(new RoutineSignalLink(1, "target", -1));
        relay.alwaysTargetInputPorts.put("target", 2);
        routine.nodes.add(relay);

        Routine copy = RoutineGraph.copy(routine);
        RoutineNode copied = copy.nodes.get(0);

        assertEquals(3, copied.signalInputCount);
        assertEquals(2, copied.signalOutputCount);
        assertEquals(1, copied.signalLinks.get(0).outputPort);
        assertEquals("target", copied.signalLinks.get(0).targetNodeId);
        assertEquals(2, copied.alwaysTargetInputPorts.get("target"));
    }

    @Test
    void timerIsAOneInputOneOutputPulseNode() {
        Routine routine = new Routine("Timer");
        RoutineNode start = new RoutineNode(RoutineNode.START_COMMAND);
        RoutineNode timer = new RoutineNode(RoutineNode.TIMER_COMMAND);
        RoutineNode work = new RoutineNode("walk");
        start.onSuccess = timer.id;
        timer.signalLinks.add(new RoutineSignalLink(0, work.id, -1));
        routine.nodes.add(start);
        routine.nodes.add(timer);
        routine.nodes.add(work);

        assertTrue(timer.isPulseNode());
        assertEquals(timer, RoutineGraph.nextSequentialNode(routine, -1));
        assertEquals(work, RoutineGraph.outgoing(routine, timer).get(0));
        assertEquals(work, RoutineGraph.nextSequentialNode(routine, routine.indexOf(timer)));
        assertTrue(RoutineConnectionAudit.firstIssue(routine).isEmpty());
    }

    @Test
    void eventSourcesCanFeedCounterAndEnd() {
        Routine routine = new Routine("Pulse tools");
        RoutineNode observer = new RoutineNode(RoutineNode.OBSERVER_COMMAND);
        RoutineNode counter = new RoutineNode(RoutineNode.COUNTER_COMMAND);
        RoutineNode end = new RoutineNode(RoutineNode.END_COMMAND);
        counter.params.put("count", "3");
        observer.signalLinks.add(new RoutineSignalLink(0, counter.id, -1));
        counter.signalLinks.add(new RoutineSignalLink(0, end.id, -1));
        routine.nodes.add(observer);
        routine.nodes.add(counter);
        routine.nodes.add(end);

        assertTrue(observer.isSourceNode());
        assertTrue(counter.isPulseNode());
        assertTrue(end.isPulseNode());
        assertEquals(counter, RoutineGraph.outgoing(routine, observer).get(0));
        assertEquals(end, RoutineGraph.outgoing(routine, counter).get(0));
        assertTrue(RoutineGraph.outgoing(routine, end).isEmpty());
        assertTrue(RoutineConnectionAudit.firstIssue(routine).isEmpty());
    }
}
