package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural checks for the jobs every new profile receives. */
class DefaultTasksTest {

    private static final List<String> EXPECTED_NAMES = List.of(
            "Woodland Cleanup",
            "Regenerative Farm Shift",
            "Ironworks Supply Run",
            "Deepcore Diamond Expedition",
            "Dragonfall Mission Control",
            "A Day in the Life");

    @Test
    void shipsExactlyTheNewSixJobsInDifficultyOrder() {
        List<TaskGraph> tasks = DefaultTasks.create();
        assertEquals(EXPECTED_NAMES, tasks.stream().map(task -> task.name).toList());

        int previousSize = 0;
        for (TaskGraph task : tasks) {
            assertTrue(task.nodes.size() > previousSize,
                    task.name + " should be more structurally ambitious than the job before it");
            previousSize = task.nodes.size();
        }
    }

    @Test
    void everyConnectionIsInternalAndEveryGraphPassesTheSharedAudit() {
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                assertTarget(task, node, "Success", node.onSuccess);
                assertTarget(task, node, "Fail", node.onFailure);
                assertTarget(task, node, "While", node.onWhile);
                if (node.onWhile != null) {
                    assertTrue(node.whileVisible,
                            task.name + ": " + node.id + " hides its While protection output");
                }

                for (String target : node.alwaysTargets) {
                    assertNotNull(task.nodeById(target),
                            task.name + ": clock points at missing node " + target);
                }
                if (node.observedNodeId != null) {
                    assertNotNull(task.nodeById(node.observedNodeId),
                            task.name + ": Observer watches missing node " + node.observedNodeId);
                }
                for (TaskSignalLink link : node.signalLinks) {
                    assertNotNull(task.nodeById(link.targetNodeId),
                            task.name + ": pulse points at missing node " + link.targetNodeId);
                }
                for (TaskDataLink link : node.inputLinks.values()) {
                    assertNotNull(task.nodeById(link.sourceNodeId),
                            task.name + ": data wire reads missing node " + link.sourceNodeId);
                }
            }
            assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty(),
                    () -> task.name + ": " + TaskConnectionAudit.firstIssue(task)
                            .map(TaskConnectionAudit.Issue::message).orElse("unknown issue"));
        }
    }

    @Test
    void nodeIdsAreUniqueAndCardsAreRunnable() {
        for (TaskGraph task : DefaultTasks.create()) {
            Set<String> seen = new HashSet<>();
            long actions = 0;
            for (TaskNode node : task.nodes) {
                assertTrue(seen.add(node.id), task.name + " reuses node id " + node.id);
                assertFalse(node.commandId.isBlank(), task.name + ": " + node.id + " has no command");
                if (!node.isSourceNode() && !node.isPulseNode()
                        && !"self_preservation".equals(node.commandId)) {
                    actions++;
                }
                assertNotNull(node.editorX, task.name + ": " + node.id + " has no canvas X");
                assertNotNull(node.editorY, task.name + ": " + node.id + " has no canvas Y");
            }
            assertTrue(actions > 0, task.name + " has no real work card");
        }
    }

    @Test
    void layoutsLeaveRoomForStraightCablesAndRightHandConvergence() {
        TaskGraph ironworks = task("Ironworks Supply Run");
        assertEquals(ironworks.nodeById("mine").editorX, ironworks.nodeById("roam").editorX,
                "the prospecting detour should sit below Mine");

        TaskGraph deepcore = task("Deepcore Diamond Expedition");
        assertEquals(deepcore.nodeById("scout").editorX, deepcore.nodeById("first_strip").editorX,
                "the first stripmine should sit below Scout");
        assertEquals(deepcore.nodeById("quota").editorX, deepcore.nodeById("second_strip").editorX,
                "the second stripmine should sit below the quota branch");
        assertTrue(deepcore.nodeById("cashout").editorX > deepcore.nodeById("quota").editorX,
                "the expedition should converge toward the right");

        TaskGraph dragonfall = task("Dragonfall Mission Control");
        assertEquals(dragonfall.nodeById("mission_end").editorX,
                dragonfall.nodeById("mission_watch").editorX,
                "the debrief observer should sit below the mission it watches");
        assertTrue(dragonfall.nodeById("debrief_pause_end").editorX
                        > dragonfall.nodeById("mission_end").editorX,
                "the debrief should expand into the right-hand support lanes");
        assertTrue(deepcore.nodeById("guard").editorX
                        > deepcore.nodeById("first_strip").editorX,
                "the shared safety lane should sit beneath the branch convergence");

        TaskGraph day = task("A Day in the Life");
        for (String[] pair : new String[][] {
                {"dawn_hunger", "dawn_eat"},
                {"morning_chop", "morning_roam"},
                {"morning_pick", "morning_forge_pick"},
                {"midday_tool_check", "midday_upgrade"},
                {"dusk_bed", "dusk_wool"},
                {"night_sword", "night_forge_sword"}}) {
            TaskNode gate = day.nodeById(pair[0]);
            TaskNode detour = day.nodeById(pair[1]);
            assertEquals(gate.editorX, detour.editorX,
                    pair[1] + " should sit directly below the gate that chooses it");
            assertTrue(detour.editorY > gate.editorY, pair[1] + " should sit below its gate");
        }
        assertTrue(day.nodeById("night_scan").editorY > day.nodeById("dusk_crops").editorY,
                "each phase should occupy its own band down the canvas");
    }

    @Test
    void everyJobHasARealPowerSource() {
        for (TaskGraph task : DefaultTasks.create()) {
            TaskNode start = TaskWiring.explicitStart(task);
            assertTrue(start != null || TaskWiring.hasAlwaysNode(task),
                    task.name + " has neither START nor a clock source");
            if (start != null) {
                assertNotNull(start.onSuccess, task.name + " has an unconnected START");
            }
        }
    }

    @Test
    void woodlandCleanupStaysSmallAndLinear() {
        TaskGraph cleanup = task("Woodland Cleanup");
        assertEquals(4, cleanup.nodes.size());
        assertEquals("chop", TaskWiring.explicitStart(cleanup).onSuccess);
        assertEquals("loot", cleanup.nodeById("chop").onSuccess);
        assertEquals("cleanup_end", cleanup.nodeById("loot").onSuccess);
        assertEquals("cleanup_end", cleanup.nodeById("loot").onFailure);
    }

    @Test
    void ironworksHasAQuotaLoopAndABoundedSearchExit() {
        TaskGraph ironworks = task("Ironworks Supply Run");
        assertEquals("mine", ironworks.nodeById("quota").onFailure);
        assertEquals("roam", ironworks.nodeById("mine").onFailure);
        assertEquals("mine", ironworks.nodeById("roam").onSuccess);
        assertNull(ironworks.nodeById("roam").onFailure,
                "an exhausted six-direction search should fail instead of wandering forever");
    }

    @Test
    void theDayIsLongButAddsNoNewCircuitVocabulary() {
        TaskGraph day = task("A Day in the Life");
        assertTrue(day.nodes.size() >= 30,
                "the closing job earns its place by length, not by new card types");
        assertNotNull(TaskWiring.explicitStart(day));
        assertTrue(TaskSafety.hasMonitor(day));

        for (TaskNode node : day.nodes) {
            assertFalse(node.isPulseNode() && !node.isEndNode(),
                    day.name + ": " + node.id + " uses a pulse card the plain vocabulary avoids");
            assertFalse(node.isClockNode(),
                    day.name + ": " + node.id + " uses a clock the plain vocabulary avoids");
            assertTrue(node.inputLinks.isEmpty(),
                    day.name + ": " + node.id + " uses a data wire the plain vocabulary avoids");
        }
    }

    /** The point of the job: work hands forward, so a branch skips ahead rather than looping. */
    @Test
    void theDayRunsForwardWithExactlyOneRetryWire() {
        TaskGraph day = task("A Day in the Life");
        List<String> order = day.nodes.stream().map(node -> node.id).toList();

        List<String> backward = new ArrayList<>();
        for (TaskNode node : day.nodes) {
            for (String target : Stream.of(node.onSuccess, node.onFailure)
                    .filter(Objects::nonNull).toList()) {
                if (order.indexOf(target) <= order.indexOf(node.id)) {
                    backward.add(node.id + " -> " + target);
                }
            }
        }
        assertEquals(List.of("morning_roam -> morning_chop"), backward,
                "only the treeless-clearing search should send the day backwards");

        assertEquals("dawn_hunger", TaskWiring.explicitStart(day).onSuccess);
        assertEquals("morning_axe", day.nodeById("dawn_loot").onSuccess);
        assertEquals("midday_iron", day.nodeById("morning_coal_loot").onSuccess);
        assertEquals("dusk_crops", day.nodeById("midday_eat").onSuccess);
        assertEquals("night_scan", day.nodeById("dusk_bed").onSuccess);
        assertEquals("day_end", day.nodeById("night_stash").onSuccess);
    }

    @Test
    void deepcoreUsesOneOreSelectionAcrossItsMiningCards() {
        TaskGraph deepcore = task("Deepcore Diamond Expedition");
        TaskNode scout = deepcore.nodeById("scout");
        assertTrue(scout.exposedOutputs.contains("targets"));

        for (String id : List.of("first_strip", "mine_first", "second_strip", "mine_final")) {
            TaskNode target = deepcore.nodeById(id);
            String input = target.commandId.equals("stripmine") ? "target" : "targets";
            TaskDataLink link = target.inputLinks.get(input);
            assertNotNull(link, id + " does not receive the shared ore choice");
            assertEquals("scout", link.sourceNodeId);
            assertEquals("targets", link.sourcePort);
            assertEquals("guard", target.onWhile);
            assertTrue(target.whileVisible, id + " hides its Self Preservation While output");
        }
    }

    @Test
    void dragonfallUsesTheAdvancedCircuitVocabularyForARealMission() {
        TaskGraph dragonfall = task("Dragonfall Mission Control");
        assertTrue(dragonfall.nodes.stream().anyMatch(TaskNode::isPulseSourceNode));
        assertTrue(dragonfall.nodes.stream().anyMatch(TaskNode::isObserverNode));
        assertTrue(dragonfall.nodes.stream().anyMatch(TaskNode::isButtonNode));
        assertTrue(dragonfall.nodes.stream().anyMatch(TaskNode::isCounterNode));
        assertTrue(dragonfall.nodes.stream().anyMatch(TaskNode::isTimerNode));
        assertTrue(dragonfall.nodes.stream().anyMatch(TaskNode::isSignalRelayNode));
        assertTrue(dragonfall.nodes.stream().anyMatch(TaskNode::isEndNode));
        assertEquals("guard", dragonfall.nodeById("launch").onWhile);
        assertTrue(dragonfall.nodeById("launch").whileVisible);
        assertEquals("mission_loot", dragonfall.nodeById("launch").onSuccess);
        assertEquals("mission_end", dragonfall.nodeById("mission_loot").onSuccess);
        assertEquals("mission_end", dragonfall.nodeById("mission_watch").observedNodeId);
        assertEquals("2", dragonfall.nodeById("closeout_counter").params.get("count"));
        assertEquals(2, dragonfall.nodeById("closeout_hub").signalOutputCount);
        assertEquals(2, dragonfall.nodeById("emergency_hub").signalOutputCount);
    }

    @Test
    void restoringDefaultsProducesIndependentCopies() {
        TaskGraph first = DefaultTasks.create().getFirst();
        first.name = "edited";
        first.nodes.clear();

        TaskGraph fresh = DefaultTasks.create().getFirst();
        assertEquals("Woodland Cleanup", fresh.name);
        assertFalse(fresh.nodes.isEmpty());
    }

    private static TaskGraph task(String name) {
        return DefaultTasks.create().stream()
                .filter(task -> task.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertTarget(TaskGraph task, TaskNode source, String kind, String target) {
        if (target != null) {
            assertNotNull(task.nodeById(target),
                    task.name + ": " + source.id + " " + kind + " points at missing node " + target);
        }
    }
}
