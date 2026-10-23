package com.etka.lune.task.runner;

import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runner with a body that is not the player's: a log of what its cards did. This is what a
 * Lumi's run is, minus the Lumi - so whatever holds here holds on the server.
 */
class GraphRunTest {

    /** Everything a test card does goes in here. */
    static final class Body {
        final List<String> log = new ArrayList<>();
    }

    /** "ok" succeeds, "fail" fails, "slow" works for two ticks first; anything else is unknown. */
    static final class FakeRun extends GraphRun<Body> {
        final List<TaskNode> failures = new ArrayList<>();

        FakeRun(TaskGraph graph) {
            super(graph);
        }

        @Override
        protected Step<Body> buildStep(TaskNode node) {
            return switch (node.commandId) {
                case "ok" -> new Card(node.id, 0, TaskStatus.SUCCESS);
                case "fail" -> new Card(node.id, 0, TaskStatus.FAILED);
                case "slow" -> new Card(node.id, 2, TaskStatus.SUCCESS);
                default -> null;
            };
        }

        @Override
        protected Object nodeName(TaskNode node) {
            return new StatusText().set("test.card." + (node == null ? "none" : node.commandId));
        }

        @Override
        protected void failedAt(TaskNode node) {
            failures.add(node);
        }
    }

    record Card(String id, int ticks, TaskStatus outcome, int[] done) implements Step<Body> {
        Card(String id, int ticks, TaskStatus outcome) {
            this(id, ticks, outcome, new int[1]);
        }

        @Override
        public void start(Body body) {
            body.log.add(id + " start");
        }

        @Override
        public TaskStatus tick(Body body) {
            body.log.add(id + " tick");
            return done[0]++ < ticks ? TaskStatus.RUNNING : outcome;
        }

        @Override
        public void stop(Body body) {
            body.log.add(id + " stop");
        }

        @Override
        public StatusText statusLine() {
            return outcome == TaskStatus.FAILED
                    ? new StatusText().set("test.reason", 7) : StatusText.EMPTY;
        }
    }

    @Test
    void cardsRunInListOrderWithNoWiring() {
        TaskNode first = node("ok", "a");
        TaskNode second = node("ok", "b");
        Body body = new Body();
        assertEquals(TaskStatus.SUCCESS, runToEnd(graph(first, second), body));
        assertEquals(List.of("a start", "a tick", "a stop", "b start", "b tick", "b stop"), body.log);
    }

    @Test
    void aFailureFollowsItsFailWireAndAnUnwiredFailureEndsTheRun() {
        TaskNode failing = node("fail", "f");
        TaskNode rescue = node("ok", "r");
        failing.onFailure = rescue.id;
        FakeRun wired = new FakeRun(graph(failing, rescue));
        assertEquals(TaskStatus.SUCCESS, runToEnd(wired, new Body()));
        assertTrue(wired.failures.isEmpty());

        TaskNode alone = node("fail", "g");
        FakeRun unwired = new FakeRun(graph(alone));
        assertEquals(TaskStatus.FAILED, runToEnd(unwired, new Body()));
        assertEquals(List.of(alone), unwired.failures);
        assertEquals("lune.status.task_runner.step_failed_because", unwired.statusLine().key());
    }

    @Test
    void aBreakpointHoldsOnlyARunWhoseBodySaysItHolds() {
        TaskNode card = node("ok", "a");
        card.breakpoint = true;
        assertEquals(TaskStatus.SUCCESS, runToEnd(graph(card), new Body()),
                "the canvas's breakpoints belong to the player's run, not to the graph");
    }

    @Test
    void aCardNameIsWhateverTheBodySaysItIs() {
        TaskNode slow = node("slow", "s");
        FakeRun run = new FakeRun(graph(slow));
        Body body = new Body();
        run.onStart(body);
        assertEquals(TaskStatus.RUNNING, run.onTick(body));
        StatusText line = run.statusLine();
        assertEquals("lune.status.task_runner.step", line.key());
        StatusText name = assertInstanceOf(StatusText.class, line.args()[2]);
        assertEquals("test.card.slow", name.key());
    }

    @Test
    void aFailedCircuitGivesItsReasonAsAStatusNotAsWords() {
        TaskNode clock = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode failing = node("fail", "c");
        clock.alwaysTargets.add(failing.id);
        FakeRun run = new FakeRun(graph(clock, failing));
        assertEquals(TaskStatus.FAILED, runToEnd(run, new Body()));
        StatusText line = run.statusLine();
        assertEquals("lune.status.task_runner.circuit_failed_because", line.key());
        StatusText reason = assertInstanceOf(StatusText.class, line.args()[1]);
        assertEquals("test.reason", reason.key());
        assertEquals(7, reason.args()[0]);
    }

    @Test
    void aCardTheBodyCannotBuildFailsTheRun() {
        FakeRun run = new FakeRun(graph(node("nope", "n")));
        assertEquals(TaskStatus.FAILED, runToEnd(run, new Body()));
        assertEquals("lune.status.task_runner.unknown_command", run.statusLine().key());
    }

    @Test
    void anOrdinaryCardOnAWhilePinIsWrappedAndAGuardIsNot() {
        FakeRun run = new FakeRun(graph());
        Card plain = new Card("p", 0, TaskStatus.SUCCESS);
        Guard<Body> wrapped = run.wrapGuard(plain);
        assertInstanceOf(StepGuard.class, wrapped);
        assertTrue(wrapped.shouldTakeControl(new Body()));
        assertSame(wrapped, run.wrapGuard(wrapped));
        assertNull(run.wrapGuard(null));
    }

    private static TaskStatus runToEnd(TaskGraph graph, Body body) {
        return runToEnd(new FakeRun(graph), body);
    }

    private static TaskStatus runToEnd(FakeRun run, Body body) {
        run.onStart(body);
        for (int tick = 0; tick < 50; tick++) {
            TaskStatus result = run.onTick(body);
            if (result != TaskStatus.RUNNING) {
                run.onStop(body);
                return result;
            }
        }
        run.onStop(body);
        return TaskStatus.RUNNING;
    }

    private static TaskNode node(String command, String id) {
        TaskNode node = new TaskNode(command);
        node.id = id;
        return node;
    }

    private static TaskGraph graph(TaskNode... nodes) {
        TaskGraph graph = new TaskGraph("Runner test");
        graph.nodes.addAll(List.of(nodes));
        return graph;
    }
}
