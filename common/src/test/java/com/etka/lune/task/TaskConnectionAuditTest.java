package com.etka.lune.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskConnectionAuditTest {

    @Test
    void acceptsPlainFallthroughAndATerminalCard() {
        TaskGraph task = new TaskGraph("Plain");
        task.nodes.add(new TaskNode("walk"));
        task.nodes.add(new TaskNode("mine"));

        assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty());
    }

    @Test
    void reportsOnlyConnectionsThatAreActuallyBrokenOrRequired() {
        TaskGraph dangling = new TaskGraph("Dangling");
        TaskNode work = new TaskNode("mine");
        work.onSuccess = "gone";
        dangling.nodes.add(work);
        assertTrue(TaskConnectionAudit.firstIssue(dangling).orElseThrow().message()
                .contains("success connection"));
        TaskSuggestion suggestion = TaskSuggestion.firstFor(dangling, work, java.util.Set.of())
                .orElseThrow();
        assertEquals(TaskSuggestion.Kind.CONNECTION, suggestion.kind());
        assertFalse(suggestion.changesTask());

        TaskGraph alwaysTask = new TaskGraph("Always");
        alwaysTask.nodes.add(new TaskNode(TaskNode.ALWAYS_COMMAND));
        alwaysTask.nodes.add(new TaskNode("walk"));
        assertTrue(TaskConnectionAudit.firstIssue(alwaysTask).orElseThrow().message()
                .contains("no action connected"));
    }

    @Test
    void reportsCardsSkippedByAnExplicitJump() {
        TaskGraph task = new TaskGraph("Jump");
        TaskNode first = new TaskNode("walk");
        TaskNode skipped = new TaskNode("eat");
        TaskNode last = new TaskNode("mine");
        first.onSuccess = last.id;
        task.nodes.add(first);
        task.nodes.add(skipped);
        task.nodes.add(last);

        TaskConnectionAudit.Issue issue = TaskConnectionAudit.firstIssue(task).orElseThrow();
        assertSame(skipped, issue.node());
        assertTrue(issue.message().contains("isn't connected"));
    }

    @Test
    void acceptsMonitorMetadataButFindsBrokenDataWires() {
        TaskGraph task = new TaskGraph("Data");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode guard = new TaskNode("self_preservation");
        TaskNode source = new TaskNode("check_item");
        TaskNode destination = new TaskNode("mine");
        always.alwaysTargets.add(guard.id);
        source.params.put("count", "10");
        source.exposedOutputs.add("count");
        destination.exposedInputs.add("limit");
        destination.inputLinks.put("limit", new TaskDataLink(source.id, "count"));
        task.nodes.add(always);
        task.nodes.add(guard);
        task.nodes.add(source);
        task.nodes.add(destination);
        assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty());

        source.exposedOutputs.clear();
        assertTrue(TaskConnectionAudit.firstIssue(task).orElseThrow().message()
                .contains("output is not connected"));
    }

    @Test
    void treatsAlwaysTargetsAsIndependentCircuitEntries() {
        TaskGraph task = new TaskGraph("Two circuits");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode background = new TaskNode("chop");
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        TaskNode main = new TaskNode("walk");
        always.alwaysTargets.add(background.id);
        start.onSuccess = main.id;
        task.nodes.add(always);
        task.nodes.add(background);
        task.nodes.add(start);
        task.nodes.add(main);

        assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty());
    }

    @Test
    void treatsAlwaysSafetyAsPoweredWithoutAStartPath() {
        TaskGraph task = new TaskGraph("Always safety");
        TaskNode always = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode safety = new TaskNode("self_preservation");
        always.alwaysTargets.add(safety.id);
        task.nodes.add(always);
        task.nodes.add(safety);

        assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty());
    }

    @Test
    void requiresAnExplicitStartConnectionWhenStartIsPresent() {
        TaskGraph task = new TaskGraph("Explicit");
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        task.nodes.add(start);
        task.nodes.add(new TaskNode("mine"));

        TaskConnectionAudit.Issue issue = TaskConnectionAudit.firstIssue(task).orElseThrow();
        assertSame(start, issue.node());
        assertTrue(issue.message().contains("START"));
    }
}
