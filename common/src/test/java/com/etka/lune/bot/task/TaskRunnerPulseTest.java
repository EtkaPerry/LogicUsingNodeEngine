package com.etka.lune.bot.task;

import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import com.etka.lune.task.TaskPower;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskRunnerPulseTest {

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }


    @Test
    void timerPulseIsNotCoalescedWithTheCircuitItJustEmitted() throws ReflectiveOperationException {
        TaskRunner runner = new TaskRunner(new TaskGraph("Timer loop"));
        TaskNode timer = new TaskNode(TaskNode.TIMER_COMMAND);
        TaskNode target = new TaskNode("work");

        addCircuit(runner, newCircuit(runner, target, timer));
        queuePulse(runner, target, timer);

        assertEquals(1, pendingCircuits(runner).size());
    }

    @Test
    void directClockPulseStillCoalescesWithItsActiveCircuit() throws ReflectiveOperationException {
        TaskRunner runner = new TaskRunner(new TaskGraph("Clock loop"));
        TaskNode clock = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode target = new TaskNode("work");

        addCircuit(runner, newCircuit(runner, target, clock));
        queuePulse(runner, target, clock);

        assertEquals(0, pendingCircuits(runner).size());
    }

    @Test
    void relayPulseStillCoalescesWithItsActiveCircuit() throws ReflectiveOperationException {
        TaskRunner runner = new TaskRunner(new TaskGraph("Relay loop"));
        TaskNode relay = new TaskNode(TaskNode.SIGNAL_RELAY_COMMAND);
        TaskNode target = new TaskNode("work");

        addCircuit(runner, newCircuit(runner, target, relay));
        queuePulse(runner, target, relay);

        assertEquals(0, pendingCircuits(runner).size());
    }

    @Test
    void completedProcessingCardsDoNotStayLitThroughTheirChildren() throws ReflectiveOperationException {
        for (String command : List.of(TaskNode.TIMER_COMMAND, TaskNode.COUNTER_COMMAND,
                TaskNode.SIGNAL_RELAY_COMMAND)) {
            TaskRunner runner = new TaskRunner(new TaskGraph("Processing pulse"));
            TaskNode origin = new TaskNode(command);
            TaskNode target = new TaskNode("work");
            queuePulse(runner, target, origin);
            assertFalse(runner.livePower().isLive(origin), command);
            assertTrue(runner.livePower().isLive(target), command);
        }
    }

    @Test
    void sourceAndWireExpireWhileReceivingWorkRemainsActive() throws ReflectiveOperationException {
        TaskRunner runner = new TaskRunner(new TaskGraph("Source pulse"));
        TaskNode source = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode target = new TaskNode("work");
        String wire = TaskPower.wire(source.id, TaskPower.ALWAYS, 0, target.id);
        Method queue = TaskRunner.class.getDeclaredMethod("queueParallelCircuit",
                com.etka.lune.bot.BotContext.class, TaskNode.class, TaskNode.class,
                TaskNode.class, String.class);
        queue.setAccessible(true);
        queue.invoke(runner, null, target, null, source, wire);
        assertTrue(runner.livePower().isLive(source));
        assertTrue(runner.livePower().isLiveWire(source, TaskPower.ALWAYS, 0, target));
        expirePulses(runner, "wirePulses");
        expirePulses(runner, "sourcePulses");
        assertFalse(runner.livePower().isLive(source));
        assertFalse(runner.livePower().isLiveWire(source, TaskPower.ALWAYS, 0, target));
        assertTrue(runner.livePower().isLive(target));
    }

    @Test
    void primarySuccessAndFailureWiresExpireWithoutDarkeningTheTarget() throws ReflectiveOperationException {
        for (int kind : List.of(TaskPower.SUCCESS, TaskPower.FAILURE)) {
            TaskGraph graph = new TaskGraph("Primary pulse");
            TaskNode from = new TaskNode("work");
            TaskNode to = new TaskNode("work");
            graph.nodes.add(from);
            graph.nodes.add(to);
            TaskRunner runner = new TaskRunner(graph);
            Field node = TaskRunner.class.getDeclaredField("node");
            node.setAccessible(true);
            node.set(runner, from);
            Method advance = TaskRunner.class.getDeclaredMethod("advance", String.class, int.class);
            advance.setAccessible(true);
            advance.invoke(runner, to.id, kind);
            assertTrue(runner.livePower().isLiveWire(from, kind, 0, to));
            expirePulses(runner, "wirePulses");
            assertFalse(runner.livePower().isLiveWire(from, kind, 0, to));
            assertTrue(runner.livePower().isLive(to));
            runner.onStop(null);
            assertTrue(runner.livePower().isEmpty());
        }
    }

    @Test
    void expiredWireSparkDoesNotRestartEvenInAnOldSnapshot() {
        TaskNode from = new TaskNode("work");
        TaskNode to = new TaskNode("work");
        TaskPower power = new TaskPower(java.util.Set.of(to.id), java.util.Set.of(), Map.of(
                TaskPower.wire(from.id, TaskPower.SUCCESS, 0, to.id),
                new TaskPower.Pulse(System.nanoTime() - TaskPower.PULSE_NANOS * 2,
                        System.nanoTime() - TaskPower.PULSE_NANOS * 2)));
        assertEquals(-1, power.wireProgress(from, TaskPower.SUCCESS, 0, to));
        assertFalse(power.isLiveWire(from, TaskPower.SUCCESS, 0, to));
        assertTrue(power.isLive(to));
    }

    @Test
    void absorbedClockPulsesRefreshActivityWithoutDuplicatingWork() throws ReflectiveOperationException {
        TaskRunner runner = new TaskRunner(new TaskGraph("Continuous source"));
        TaskNode clock = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode target = new TaskNode("work");
        addCircuit(runner, newCircuit(runner, target, clock));
        for (int tick = 0; tick < 100; tick++) {
            expirePulses(runner, "sourcePulses");
            expirePulses(runner, "wirePulses");
            queuePulse(runner, target, clock);
            assertTrue(runner.livePower().isLive(clock));
            assertTrue(runner.livePower().isLive(target));
            assertEquals(0, pendingCircuits(runner).size());
            assertEquals(1, circuits(runner, "parallelCircuits").size());
        }
    }

    @Test
    void frequentPulsesAdvanceTheirSparkAndStopAfterTheLastEmission() {
        long period = TaskPower.PULSE_NANOS;
        TaskPower.Pulse pulse = new TaskPower.Pulse(0, 0);
        for (int frame = 1; frame <= 30; frame++) {
            long now = frame * period / 10;
            pulse = pulse.refresh(now);
            assertEquals((frame % 10) / 10.0, pulse.progress(now), 0.00001);
        }
        assertEquals(-1, pulse.progress(pulse.latest() + period));
        TaskPower.Pulse restarted = pulse.refresh(pulse.latest() + period * 2);
        assertEquals(0, restarted.progress(restarted.latest()));
    }

    @Test
    void intervalClockStaysArmedBetweenPulses() throws ReflectiveOperationException {
        TaskNode clock = new TaskNode(TaskNode.PULSE_COMMAND);
        clock.alwaysIntervalSeconds = 1;
        TaskNode end = new TaskNode(TaskNode.END_COMMAND);
        clock.alwaysTargets.add(end.id);
        TaskRunner runner = runnerWith(clock, end);
        for (int tick = 0; tick < 61; tick++) {
            assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null));
            assertEquals(tick % 20 == 0, runner.livePower().isLive(end), "tick " + tick);
        }
    }

    @Test
    void buttonRemainsArmedAfterConsumingItsPulse() {
        TaskNode button = new TaskNode(TaskNode.BUTTON_COMMAND);
        TaskNode end = new TaskNode(TaskNode.END_COMMAND);
        connect(button, end);
        TaskRunner runner = runnerWith(button, end);
        assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null));
        assertFalse(runner.livePower().isLive(end));
        for (int press = 0; press < 3; press++) {
            TaskRunner.pressButton(button);
            assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null));
            assertTrue(runner.livePower().isLive(end));
            assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null));
            assertFalse(runner.livePower().isLive(end));
        }
    }

    @Test
    void finiteTimerStopsAfterItsOutputFinishes() {
        TaskNode start = new TaskNode(TaskNode.START_COMMAND);
        TaskNode timer = new TaskNode(TaskNode.TIMER_COMMAND);
        timer.params.put("seconds", "0");
        TaskNode end = new TaskNode(TaskNode.END_COMMAND);
        start.onSuccess = timer.id;
        connect(timer, end);
        TaskRunner runner = runnerWith(start, timer, end);
        assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null));
        assertFalse(runner.livePower().isLive(timer));
        assertTrue(runner.livePower().isLive(end));
        assertEquals(com.etka.lune.bot.TaskStatus.SUCCESS, runner.onTick(null));
    }

    @Test
    void repeatSuccessStillTicksIndependentCircuits() throws ReflectiveOperationException {
        for (int repeat : List.of(0, 3)) {
            TaskNode start = new TaskNode(TaskNode.START_COMMAND);
            TaskNode work = new TaskNode("work");
            work.repeat = repeat;
            start.onSuccess = work.id;
            TaskNode end = new TaskNode(TaskNode.END_COMMAND);
            TaskRunner runner = runnerWith(start, work, end);
            Field task = TaskRunner.class.getDeclaredField("task");
            task.setAccessible(true);
            task.set(runner, new com.etka.lune.bot.Task() {
                public String name() { return "Instant test step"; }
                public com.etka.lune.bot.StatusText statusLine() {
                    return new com.etka.lune.bot.StatusText();
                }
                public com.etka.lune.bot.TaskStatus onTick(com.etka.lune.bot.BotContext ctx) {
                    return com.etka.lune.bot.TaskStatus.SUCCESS;
                }
            });
            addCircuit(runner, newCircuit(runner, end, start));
            runner.onTick(null);
            assertEquals(0, circuits(runner, "parallelCircuits").size(), "repeat " + repeat);
        }
    }

    @Test
    void observerEmitsOnBothEdgesAndStaysArmed() throws ReflectiveOperationException {
        TaskNode watched = new TaskNode(TaskNode.END_COMMAND);
        TaskNode observer = new TaskNode(TaskNode.OBSERVER_COMMAND);
        observer.observedNodeId = watched.id;
        TaskNode sink = new TaskNode(TaskNode.END_COMMAND);
        connect(observer, sink);
        TaskRunner runner = runnerWith(observer, watched, sink);
        Field node = TaskRunner.class.getDeclaredField("node");
        node.setAccessible(true);
        node.set(runner, watched);
        assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null)); // baseline
        assertFalse(runner.livePower().isLive(sink));
        assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null)); // falling edge
        assertTrue(runner.livePower().isLive(sink));
        runner.onTick(null);
        assertFalse(runner.livePower().isLive(sink));
        node.set(runner, watched);
        runner.onTick(null); // rising edge
        assertTrue(runner.livePower().isLive(sink));
    }

    @Test
    void counterForwardsOnlyEveryConfiguredNumberOfButtonPresses() {
        TaskNode button = new TaskNode(TaskNode.BUTTON_COMMAND);
        TaskNode counter = new TaskNode(TaskNode.COUNTER_COMMAND);
        counter.params.put("count", "3");
        TaskNode sink = new TaskNode(TaskNode.END_COMMAND);
        connect(button, counter);
        connect(counter, sink);
        TaskRunner runner = runnerWith(button, counter, sink);
        for (int press = 1; press <= 6; press++) {
            TaskRunner.pressButton(button);
            runner.onTick(null);
            runner.onTick(null);
            assertFalse(runner.livePower().isLive(counter));
            assertEquals(press % 3 == 0, runner.livePower().isLive(sink));
            assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null));
        }
    }

    @Test
    void alwaysKeepsCheckingAnIdleMonitorWithoutCreatingDuplicateInstances() throws ReflectiveOperationException {
        TaskNode clock = new TaskNode(TaskNode.ALWAYS_COMMAND);
        TaskNode guarded = new TaskNode("selfpreservation");
        clock.alwaysTargets.add(guarded.id);
        TaskRunner runner = runnerWith(clock, guarded);
        int[] checks = {0};
        com.etka.lune.bot.WhileMonitor monitor = new com.etka.lune.bot.WhileMonitor() {
            public String name() { return "Idle monitor"; }
            public boolean shouldTakeControl(com.etka.lune.bot.BotContext ctx) {
                checks[0]++;
                return false;
            }
            public com.etka.lune.bot.TaskStatus onTick(com.etka.lune.bot.BotContext ctx) {
                fail("An idle monitor must not act");
                return com.etka.lune.bot.TaskStatus.RUNNING;
            }
        };
        for (int tick = 0; tick < 20; tick++) {
            assertEquals(com.etka.lune.bot.TaskStatus.RUNNING, runner.onTick(null));
            assertTrue(runner.livePower().isLive(clock));
            List<Object> active = circuits(runner, "parallelCircuits");
            assertTrue(active.size() <= 1);
            for (Object circuit : active) {
                Field task = circuit.getClass().getDeclaredField("task");
                task.setAccessible(true);
                task.set(circuit, monitor);
            }
        }
        assertEquals(10, checks[0]);
    }

    private static TaskRunner runnerWith(TaskNode... nodes) {
        TaskGraph graph = new TaskGraph("Signal execution test");
        graph.nodes.addAll(List.of(nodes));
        TaskRunner runner = new TaskRunner(graph);
        runner.onStart(null);
        return runner;
    }

    private static void connect(TaskNode source, TaskNode target) {
        source.signalLinks.add(new com.etka.lune.task.TaskSignalLink(0, target.id, 0));
    }

    @SuppressWarnings("unchecked")
    private static void expirePulses(TaskRunner runner, String name) throws ReflectiveOperationException {
        Field field = TaskRunner.class.getDeclaredField(name);
        field.setAccessible(true);
        long expired = System.nanoTime() - TaskPower.PULSE_NANOS * 2;
        if (name.equals("wirePulses")) {
            ((Map<String, TaskPower.Pulse>) field.get(runner)).replaceAll(
                    (key, pulse) -> new TaskPower.Pulse(expired, expired));
        } else {
            ((Map<String, Long>) field.get(runner)).replaceAll((key, start) -> expired);
        }
    }

    private static Object newCircuit(TaskRunner runner, TaskNode entry, TaskNode origin)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.etka.lune.bot.task.TaskRunner$ParallelCircuit");
        Constructor<?> constructor = type.getDeclaredConstructor(TaskRunner.class, TaskNode.class,
                TaskNode.class, TaskNode.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(runner, entry, null, origin, "wire");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> circuits(TaskRunner runner, String fieldName)
            throws ReflectiveOperationException {
        Field field = TaskRunner.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (List<Object>) field.get(runner);
    }

    private static void addCircuit(TaskRunner runner, Object circuit) throws ReflectiveOperationException {
        circuits(runner, "parallelCircuits").add(circuit);
    }

    private static List<Object> pendingCircuits(TaskRunner runner) throws ReflectiveOperationException {
        return circuits(runner, "pendingParallelCircuits");
    }

    private static void queuePulse(TaskRunner runner, TaskNode target, TaskNode origin)
            throws ReflectiveOperationException {
        Method queue = TaskRunner.class.getDeclaredMethod("queueParallelCircuit",
                com.etka.lune.bot.BotContext.class, TaskNode.class, TaskNode.class,
                TaskNode.class, String.class);
        queue.setAccessible(true);
        queue.invoke(runner, null, target, null, origin, "wire");
    }
}
