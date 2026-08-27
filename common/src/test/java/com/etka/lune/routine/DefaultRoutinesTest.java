package com.etka.lune.routine;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seeded routines are the first thing a new player sees, and a dangling edge in one would only
 * show up as a run that stops for no visible reason. The graph is plain data, so it can be checked
 * without a Minecraft runtime.
 */
class DefaultRoutinesTest {

    @Test
    void everyEdgePointsAtANodeInTheSameRoutine() {
        for (Routine routine : DefaultRoutines.create()) {
            for (RoutineNode node : routine.nodes) {
                if (node.onSuccess != null) {
                    assertNotNull(routine.nodeById(node.onSuccess),
                            routine.name + ": " + node.id + " succeeds into missing node " + node.onSuccess);
                }
                if (node.onFailure != null) {
                    assertNotNull(routine.nodeById(node.onFailure),
                            routine.name + ": " + node.id + " fails into missing node " + node.onFailure);
                }
                for (String target : node.alwaysTargets) {
                    assertNotNull(routine.nodeById(target),
                            routine.name + ": Always points at missing node " + target);
                }
            }
        }
    }

    @Test
    void nodeIdsAreUniqueWithinEachRoutine() {
        for (Routine routine : DefaultRoutines.create()) {
            Set<String> seen = new HashSet<>();
            for (RoutineNode node : routine.nodes) {
                assertTrue(seen.add(node.id), routine.name + " reuses node id " + node.id);
            }
        }
    }

    @Test
    void routineNamesAreDistinctSoOneCanCallAnother() {
        List<Routine> routines = DefaultRoutines.create();
        Set<String> names = new HashSet<>();
        for (Routine routine : routines) {
            assertTrue(names.add(routine.name.toLowerCase()), "duplicate routine name " + routine.name);
        }
        assertEquals(6, routines.size());
    }

    @Test
    void everyNodeNamesACommandAndHasAtLeastOneRealStep() {
        for (Routine routine : DefaultRoutines.create()) {
            assertFalse(routine.nodes.isEmpty(), routine.name + " has no nodes");
            long steps = routine.nodes.stream()
                    .filter(node -> !node.isAlwaysNode())
                    .filter(node -> !"self_preservation".equals(node.commandId))
                    .count();
            assertTrue(steps > 0, routine.name + " is monitors only, so it would finish instantly");
            for (RoutineNode node : routine.nodes) {
                assertFalse(node.commandId.isBlank(), routine.name + ": node " + node.id + " has no command");
            }
        }
    }

    @Test
    void everySeededRoutineHasAnEntryPoint() {
        for (Routine routine : DefaultRoutines.create()) {
            RoutineNode start = RoutineGraph.explicitStart(routine);
            boolean hasAlways = RoutineGraph.hasAlwaysNode(routine);
            assertTrue(start != null || hasAlways, routine.name + " has no entry point");
            if (start != null) {
                assertNotNull(start.onSuccess, routine.name + " START is not connected");
            }
        }
    }

    @Test
    void theTeachingRoutineActuallyBranchesLoopsAndGuards() {
        Routine trip = DefaultRoutines.create().stream()
                .filter(routine -> routine.name.equals("Safe Mining Trip"))
                .findFirst()
                .orElseThrow();

        assertTrue(trip.nodes.stream().anyMatch(RoutineNode::isAlwaysNode), "no Always monitor");

        RoutineNode dig = trip.nodeById("dig");
        assertNotNull(dig);
        assertEquals("quota", dig.onSuccess);
        assertEquals("roam", dig.onFailure, "a failed dig should go looking rather than stop");

        // Two ways back to dig: the explore detour and the unmet quota. Both are loops.
        assertEquals("dig", trip.nodeById("roam").onSuccess);
        assertEquals("dig", trip.nodeById("quota").onFailure);
    }

    @Test
    void survivalOperationsCenterUsesTheCompleteCircuitVocabulary() {
        Routine showcase = DefaultRoutines.create().stream()
                .filter(routine -> routine.name.equals("Survival Operations Center"))
                .findFirst()
                .orElseThrow();

        assertTrue(showcase.nodes.stream().anyMatch(RoutineNode::isAlwaysNode));
        assertTrue(showcase.nodes.stream().anyMatch(RoutineNode::isObserverNode));
        assertTrue(showcase.nodes.stream().anyMatch(RoutineNode::isButtonNode));
        assertTrue(showcase.nodes.stream().anyMatch(RoutineNode::isCounterNode));
        assertTrue(showcase.nodes.stream().anyMatch(RoutineNode::isTimerNode));
        assertTrue(showcase.nodes.stream().anyMatch(RoutineNode::isSignalRelayNode));
        assertTrue(showcase.nodes.stream().anyMatch(RoutineNode::isEndNode));
        assertTrue(showcase.nodes.stream().anyMatch(node -> node.onWhile != null));
        assertTrue(RoutineConnectionAudit.firstIssue(showcase).isEmpty(),
                () -> RoutineConnectionAudit.firstIssue(showcase).map(RoutineConnectionAudit.Issue::message)
                        .orElse("unknown connection issue"));
    }

    @Test
    void seedingProducesIndependentCopies() {
        Routine first = DefaultRoutines.create().get(0);
        first.name = "edited";
        first.nodes.clear();

        Routine fresh = DefaultRoutines.create().get(0);
        assertEquals("Chop 20 Logs", fresh.name);
        assertFalse(fresh.nodes.isEmpty());
    }
}
