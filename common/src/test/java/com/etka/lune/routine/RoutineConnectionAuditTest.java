package com.etka.lune.routine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoutineConnectionAuditTest {

    @Test
    void acceptsPlainFallthroughAndATerminalCard() {
        Routine routine = new Routine("Plain");
        routine.nodes.add(new RoutineNode("walk"));
        routine.nodes.add(new RoutineNode("mine"));

        assertTrue(RoutineConnectionAudit.firstIssue(routine).isEmpty());
    }

    @Test
    void reportsOnlyConnectionsThatAreActuallyBrokenOrRequired() {
        Routine dangling = new Routine("Dangling");
        RoutineNode work = new RoutineNode("mine");
        work.onSuccess = "gone";
        dangling.nodes.add(work);
        assertTrue(RoutineConnectionAudit.firstIssue(dangling).orElseThrow().message()
                .contains("success connection"));
        RoutineSuggestion suggestion = RoutineSuggestion.firstFor(dangling, work, java.util.Set.of())
                .orElseThrow();
        assertEquals(RoutineSuggestion.Kind.CONNECTION, suggestion.kind());
        assertFalse(suggestion.changesRoutine());

        Routine alwaysRoutine = new Routine("Always");
        alwaysRoutine.nodes.add(new RoutineNode(RoutineNode.ALWAYS_COMMAND));
        alwaysRoutine.nodes.add(new RoutineNode("walk"));
        assertTrue(RoutineConnectionAudit.firstIssue(alwaysRoutine).orElseThrow().message()
                .contains("no action connected"));
    }

    @Test
    void reportsCardsSkippedByAnExplicitJump() {
        Routine routine = new Routine("Jump");
        RoutineNode first = new RoutineNode("walk");
        RoutineNode skipped = new RoutineNode("eat");
        RoutineNode last = new RoutineNode("mine");
        first.onSuccess = last.id;
        routine.nodes.add(first);
        routine.nodes.add(skipped);
        routine.nodes.add(last);

        RoutineConnectionAudit.Issue issue = RoutineConnectionAudit.firstIssue(routine).orElseThrow();
        assertSame(skipped, issue.node());
        assertTrue(issue.message().contains("isn't connected"));
    }

    @Test
    void acceptsMonitorMetadataButFindsBrokenDataWires() {
        Routine routine = new Routine("Data");
        RoutineNode always = new RoutineNode(RoutineNode.ALWAYS_COMMAND);
        RoutineNode guard = new RoutineNode("self_preservation");
        RoutineNode source = new RoutineNode("check_item");
        RoutineNode destination = new RoutineNode("mine");
        always.alwaysTargets.add(guard.id);
        source.params.put("count", "10");
        source.exposedOutputs.add("count");
        destination.exposedInputs.add("limit");
        destination.inputLinks.put("limit", new RoutineDataLink(source.id, "count"));
        routine.nodes.add(always);
        routine.nodes.add(guard);
        routine.nodes.add(source);
        routine.nodes.add(destination);
        assertTrue(RoutineConnectionAudit.firstIssue(routine).isEmpty());

        source.exposedOutputs.clear();
        assertTrue(RoutineConnectionAudit.firstIssue(routine).orElseThrow().message()
                .contains("output is not connected"));
    }

    @Test
    void treatsAlwaysTargetsAsIndependentCircuitEntries() {
        Routine routine = new Routine("Two circuits");
        RoutineNode always = new RoutineNode(RoutineNode.ALWAYS_COMMAND);
        RoutineNode background = new RoutineNode("chop");
        RoutineNode start = new RoutineNode(RoutineNode.START_COMMAND);
        RoutineNode main = new RoutineNode("walk");
        always.alwaysTargets.add(background.id);
        start.onSuccess = main.id;
        routine.nodes.add(always);
        routine.nodes.add(background);
        routine.nodes.add(start);
        routine.nodes.add(main);

        assertTrue(RoutineConnectionAudit.firstIssue(routine).isEmpty());
    }

    @Test
    void treatsAlwaysSafetyAsPoweredWithoutAStartPath() {
        Routine routine = new Routine("Always safety");
        RoutineNode always = new RoutineNode(RoutineNode.ALWAYS_COMMAND);
        RoutineNode safety = new RoutineNode("self_preservation");
        always.alwaysTargets.add(safety.id);
        routine.nodes.add(always);
        routine.nodes.add(safety);

        assertTrue(RoutineConnectionAudit.firstIssue(routine).isEmpty());
    }

    @Test
    void requiresAnExplicitStartConnectionWhenStartIsPresent() {
        Routine routine = new Routine("Explicit");
        RoutineNode start = new RoutineNode(RoutineNode.START_COMMAND);
        routine.nodes.add(start);
        routine.nodes.add(new RoutineNode("mine"));

        RoutineConnectionAudit.Issue issue = RoutineConnectionAudit.firstIssue(routine).orElseThrow();
        assertSame(start, issue.node());
        assertTrue(issue.message().contains("START"));
    }
}
