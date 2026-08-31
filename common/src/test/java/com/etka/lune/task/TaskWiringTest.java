package com.etka.lune.task;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskWiringTest {

    @Test
    void insertsBeforeWithoutBreakingFallthroughOrExplicitEntries() {
        TaskGraph task = new TaskGraph("Prepare");
        TaskNode first = new TaskNode("walk");
        TaskNode alternate = new TaskNode("find");
        TaskNode target = new TaskNode("mine");
        alternate.onFailure = target.id;
        task.nodes.add(first);
        task.nodes.add(alternate);
        task.nodes.add(target);

        TaskNode inserted = TaskWiring.insertBefore(task, target, "eat",
                Map.of("minimum_food", "18"));

        assertNotNull(inserted);
        assertEquals(inserted, task.nodes.get(2));
        assertEquals(target.id, inserted.onSuccess);
        assertEquals(target.id, inserted.onFailure);
        assertEquals(inserted.id, alternate.onFailure);
        assertEquals(inserted, TaskWiring.outgoing(task, alternate).get(0));
    }

    @Test
    void insertsAfterAndPreservesFormerSuccessDestination() {
        TaskGraph task = new TaskGraph("Deposit");
        TaskNode work = new TaskNode("chop");
        TaskNode next = new TaskNode("walk");
        task.nodes.add(work);
        task.nodes.add(next);

        TaskNode inserted = TaskWiring.insertAfter(task, work, "deposit",
                Map.of("optional", "true"));

        assertNotNull(inserted);
        assertEquals(inserted.id, work.onSuccess);
        assertEquals(next.id, inserted.onSuccess);
        assertEquals(next.id, inserted.onFailure);
        assertEquals(next, task.nodes.get(2));
    }

    @Test
    void refusesToEditCyclesAndForeverCards() {
        TaskGraph task = new TaskGraph("Loop");
        TaskNode loop = new TaskNode("mine");
        loop.onSuccess = loop.id;
        task.nodes.add(loop);

        assertFalse(TaskWiring.canInsertBefore(task, loop));
        assertNull(TaskWiring.insertBefore(task, loop, "eat", Map.of()));

        loop.onSuccess = null;
        loop.repeat = 0;
        assertFalse(TaskWiring.canInsertAfter(task, loop));
    }

    @Test
    void restoreRemovesAddedNodesButKeepsOriginalObjectsAlive() {
        TaskGraph task = new TaskGraph("Undo");
        TaskNode work = new TaskNode("chop");
        work.editorX = 100;
        work.editorY = 40;
        task.nodes.add(work);
        TaskGraph snapshot = TaskWiring.copy(task);

        TaskNode added = TaskWiring.insertAfter(task, work, "deposit", Map.of());
        work.params.put("limit", "64");
        assertNotNull(added);

        assertTrue(TaskWiring.restore(task, snapshot));
        assertEquals(1, task.nodes.size());
        assertSame(work, task.nodes.get(0));
        assertNull(work.onSuccess);
        assertFalse(work.params.containsKey("limit"));
        assertEquals(100, work.editorX);
    }

    @Test
    void explicitStartChoosesItsSuccessTargetInsteadOfListOrder() {
        TaskGraph task = new TaskGraph("Explicit entry");
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        TaskNode skipped = new TaskNode("eat");
        TaskNode first = new TaskNode("mine");
        start.onSuccess = first.id;
        task.nodes.add(start);
        task.nodes.add(skipped);
        task.nodes.add(first);

        assertEquals(first, TaskWiring.nextSequentialNode(task, -1));
        assertEquals(List.of(first), TaskWiring.outgoing(task, start));
    }

    @Test
    void explicitStartWithoutAnEdgeDoesNotGuessTheFirstCard() {
        TaskGraph task = new TaskGraph("Unwired entry");
        task.nodes.add(new TaskNode(TaskNode.START_COMMAND));
        task.nodes.add(new TaskNode("mine"));

        assertNull(TaskWiring.nextSequentialNode(task, -1));
    }

    @Test
    void addingStartPreservesTheExistingEntryPoint() {
        TaskGraph task = new TaskGraph("Legacy route");
        TaskNode work = new TaskNode("mine");
        task.nodes.add(work);

        TaskNode start = TaskWiring.addExplicitStart(task);

        assertNotNull(start);
        assertEquals(start, task.nodes.get(0));
        assertEquals(work.id, start.onSuccess);
        assertEquals(work, TaskWiring.nextSequentialNode(task, -1));
    }

    @Test
    void alwaysServesAsTheImplicitEntryPoint() {
        TaskGraph task = new TaskGraph("Protected route");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode guard = new TaskNode(TaskSafety.COMMAND_ID);
        TaskNode work = new TaskNode("mine");
        TaskNode backgroundWork = new TaskNode("chop");
        always.alwaysTargets.add(guard.id);
        always.alwaysTargets.add(backgroundWork.id);
        task.nodes.add(always);
        task.nodes.add(guard);
        task.nodes.add(work);
        task.nodes.add(backgroundWork);

        assertTrue(TaskWiring.hasAlwaysNode(task));
        assertNull(TaskWiring.addExplicitStart(task));
        assertNull(TaskWiring.explicitStart(task));
        assertEquals(work, TaskWiring.nextSequentialNode(task, -1));
        assertEquals(2, always.alwaysTargets.size());
    }

    @Test
    void copiesAndDescribesAlwaysPulseFrequency() {
        TaskGraph task = new TaskGraph("Pulse");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        always.alwaysIntervalSeconds = 30;
        task.nodes.add(always);

        TaskGraph copy = TaskWiring.copy(task);

        assertEquals(30, copy.nodes.get(0).alwaysIntervalSeconds);
        assertEquals(600, copy.nodes.get(0).alwaysIntervalTicks());
        assertEquals("every 30 seconds", copy.nodes.get(0).describeAlwaysInterval());
    }

    @Test
    void copiesWhileVisibilityForSnapshots() {
        TaskGraph task = new TaskGraph("While visibility");
        TaskNode mine = new TaskNode("mine");
        mine.whileVisible = true;
        task.nodes.add(mine);

        TaskGraph copy = TaskWiring.copy(task);

        assertTrue(copy.nodes.get(0).whileVisible);
        assertTrue(mine.copy().whileVisible);
    }

    @Test
    void copiesCableAnchorsForSnapshots() {
        TaskGraph task = new TaskGraph("Cable routes");
        TaskNode source = new TaskNode("walk");
        TaskNode target = new TaskNode("mine");
        source.onSuccess = target.id;
        task.nodes.add(source);
        task.nodes.add(target);
        String key = TaskCableAnchor.key("success", source.id, target.id);
        TaskCableRoute route = new TaskCableRoute(new TaskCableAnchor(240, 96));
        route.points.add(new TaskCableAnchor(320, 140));
        task.cableAnchors.put(key, route);

        TaskGraph copy = TaskWiring.copy(task);

        assertEquals(240, copy.cableAnchors.get(key).points.get(0).x);
        assertEquals(96, copy.cableAnchors.get(key).points.get(0).y);
        assertEquals(2, copy.cableAnchors.get(key).points.size());
        assertEquals(320, copy.cableAnchors.get(key).points.get(1).x);
        assertEquals(140, copy.cableAnchors.get(key).points.get(1).y);
        assertNotSame(task.cableAnchors.get(key), copy.cableAnchors.get(key));
        assertNotSame(task.cableAnchors.get(key).points.get(0),
                copy.cableAnchors.get(key).points.get(0));
        copy.cableAnchors.get(key).points.get(0).x = 300;
        assertEquals(240, task.cableAnchors.get(key).points.get(0).x);
    }

    @Test
    void repeatedCablePullsKeepEveryRoutingPoint() {
        TaskGraph task = new TaskGraph("Multi-point cable route");
        TaskNode source = new TaskNode("walk");
        TaskNode target = new TaskNode("mine");
        source.onSuccess = target.id;
        task.nodes.add(source);
        task.nodes.add(target);
        String key = TaskCableAnchor.key("success", source.id, target.id);

        TaskCableRoute route = new TaskCableRoute();
        route.points.add(new TaskCableAnchor(120, 84));
        route.points.add(new TaskCableAnchor(260, 172));
        route.points.add(new TaskCableAnchor(180, 240));
        task.cableAnchors.put(key, route);

        assertEquals(1, task.cableAnchors.size());
        assertEquals(3, task.cableAnchors.get(key).points.size());
        assertEquals(120, task.cableAnchors.get(key).points.get(0).x);
        assertEquals(260, task.cableAnchors.get(key).points.get(1).x);
        assertEquals(180, task.cableAnchors.get(key).points.get(2).x);
    }

    @Test
    void cablePointIsInsertedIntoTheSegmentThatWasPulled() {
        TaskCableRoute route = new TaskCableRoute();
        route.insertPoint(0, new TaskCableAnchor(100, 80));
        route.insertPoint(1, new TaskCableAnchor(300, 80));

        // Pull the segment between the two existing points. Neither old point should move.
        route.insertPoint(1, new TaskCableAnchor(200, 180));

        assertEquals(3, route.points.size());
        assertEquals(100, route.points.get(0).x);
        assertEquals(200, route.points.get(1).x);
        assertEquals(180, route.points.get(1).y);
        assertEquals(300, route.points.get(2).x);
    }

    @Test
    void cableAnchorsRoundTripThroughTaskJson() {
        TaskGraph task = new TaskGraph("Cable JSON");
        TaskNode source = new TaskNode("walk");
        TaskNode target = new TaskNode("mine");
        source.onSuccess = target.id;
        task.nodes.add(source);
        task.nodes.add(target);
        String key = TaskCableAnchor.key("success", source.id, target.id);
        TaskCableRoute route = new TaskCableRoute(new TaskCableAnchor(-32, 180));
        route.points.add(new TaskCableAnchor(84, 220));
        task.cableAnchors.put(key, route);

        TaskGraph imported = new Gson().fromJson(new Gson().toJson(task), TaskGraph.class);

        assertEquals(2, imported.cableAnchors.get(key).points.size());
        assertEquals(-32, imported.cableAnchors.get(key).points.get(0).x);
        assertEquals(180, imported.cableAnchors.get(key).points.get(0).y);
        assertEquals(84, imported.cableAnchors.get(key).points.get(1).x);
        assertEquals(220, imported.cableAnchors.get(key).points.get(1).y);
    }

    @Test
    void copiesConfigurableRelayPortsAndPulseWires() {
        TaskGraph task = new TaskGraph("Relay");
        TaskNode relay = new TaskNode(TaskNode.SIGNAL_RELAY_COMMAND);
        relay.signalInputCount = 3;
        relay.signalOutputCount = 2;
        relay.signalLinks.add(new TaskSignalLink(1, "target", -1));
        relay.alwaysTargetInputPorts.put("target", 2);
        task.nodes.add(relay);

        TaskGraph copy = TaskWiring.copy(task);
        TaskNode copied = copy.nodes.get(0);

        assertEquals(3, copied.signalInputCount);
        assertEquals(2, copied.signalOutputCount);
        assertEquals(1, copied.signalLinks.get(0).outputPort);
        assertEquals("target", copied.signalLinks.get(0).targetNodeId);
        assertEquals(2, copied.alwaysTargetInputPorts.get("target"));
    }

    @Test
    void timerIsAOneInputOneOutputPulseNode() {
        TaskGraph task = new TaskGraph("Timer");
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        TaskNode timer = new TaskNode(TaskNode.TIMER_COMMAND);
        TaskNode work = new TaskNode("walk");
        start.onSuccess = timer.id;
        timer.signalLinks.add(new TaskSignalLink(0, work.id, -1));
        task.nodes.add(start);
        task.nodes.add(timer);
        task.nodes.add(work);

        assertTrue(timer.isPulseNode());
        assertEquals(timer, TaskWiring.nextSequentialNode(task, -1));
        assertEquals(work, TaskWiring.outgoing(task, timer).get(0));
        assertEquals(work, TaskWiring.nextSequentialNode(task, task.indexOf(timer)));
        assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty());
    }

    @Test
    void eventSourcesCanFeedCounterAndEnd() {
        TaskGraph task = new TaskGraph("Pulse tools");
        TaskNode observer = new TaskNode(TaskNode.OBSERVER_COMMAND);
        TaskNode counter = new TaskNode(TaskNode.COUNTER_COMMAND);
        TaskNode end = new TaskNode(TaskNode.END_COMMAND);
        counter.params.put("count", "3");
        TaskNode watched = new TaskNode("mine");
        observer.observedNodeId = watched.id;
        observer.signalLinks.add(new TaskSignalLink(0, counter.id, -1));
        counter.signalLinks.add(new TaskSignalLink(0, end.id, -1));
        task.nodes.add(watched);
        task.nodes.add(observer);
        task.nodes.add(counter);
        task.nodes.add(end);

        assertTrue(observer.isSourceNode());
        assertTrue(counter.isPulseNode());
        assertTrue(end.isPulseNode());
        assertEquals(counter, TaskWiring.outgoing(task, observer).get(0));
        assertEquals(end, TaskWiring.outgoing(task, counter).get(0));
        assertTrue(TaskWiring.outgoing(task, end).isEmpty());
        assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty());
    }

    @Test
    void powerFlowsFromEverySourceAndReachesWhileCompanions() {
        // Two independent lanes: START -> mine (with an Eat companion on its While pin), and
        // Always -> check -> guard. The stray card is deliberately listed before the entry point,
        // because fall-through only ever runs forwards through the list.
        TaskGraph task = new TaskGraph("Two lanes");
        TaskNode stray = new TaskNode("walk");
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        TaskNode mine = new TaskNode("mine");
        TaskNode companion = new TaskNode("eat");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode check = new TaskNode("condition");
        TaskNode guard = new TaskNode("self_preservation");
        start.onSuccess = mine.id;
        mine.onWhile = companion.id;
        always.alwaysTargets.add(check.id);
        check.onSuccess = guard.id;
        task.nodes.add(stray);
        task.nodes.add(start);
        task.nodes.add(mine);
        task.nodes.add(companion);
        task.nodes.add(always);
        task.nodes.add(check);
        task.nodes.add(guard);

        var powered = TaskWiring.poweredNodes(task);

        assertTrue(powered.contains(mine));
        assertTrue(powered.contains(companion));
        assertTrue(powered.contains(check));
        assertTrue(powered.contains(guard));
        assertFalse(powered.contains(stray));
        // Sources supply power; they do not receive it.
        assertFalse(powered.contains(start));
        assertFalse(powered.contains(always));
    }

    @Test
    void aForeverCardFollowsItsSuccessWireEvenWhenNothingFollowsItInTheList() {
        // Always -> Check Player (x∞) -> Success -> Self Preservation, with the guard listed
        // before the check. Judging "is there anywhere to go" by list order alone said no, and
        // the branch sat on Check Player forever.
        TaskGraph task = new TaskGraph("Watched run");
        TaskNode guard = new TaskNode("self_preservation");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode check = new TaskNode("check_player");
        check.repeat = 0;
        check.onSuccess = guard.id;
        always.alwaysTargets.add(check.id);
        task.nodes.add(guard);
        task.nodes.add(always);
        task.nodes.add(check);

        assertNull(TaskWiring.nextSequentialNode(task, task.indexOf(check)));
        assertTrue(TaskWiring.hasSuccessDestination(task, check));

        // A card with no wire and nothing after it really has nowhere to go, and must keep running.
        check.onSuccess = null;
        assertFalse(TaskWiring.hasSuccessDestination(task, check));

        // A wire pointing at a card that has since been deleted is not a destination either.
        check.onSuccess = "deleted-card";
        assertFalse(TaskWiring.hasSuccessDestination(task, check));
    }

    @Test
    void aCardWithNoWiresIntoItIsAloneEvenWhenListOrderWouldReachIt() {
        TaskGraph task = new TaskGraph("Single card");
        TaskNode only = new TaskNode("self_preservation");
        task.nodes.add(only);

        assertTrue(TaskWiring.poweredNodes(task).contains(only));
        assertFalse(TaskWiring.hasIncomingConnection(task, only));
    }
}
