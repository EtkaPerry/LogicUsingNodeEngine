package com.etka.lune.task.runner;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.WhileMonitor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The graph runner reaches every player's card through {@link Step} and {@link Guard}, whose
 * methods take a type variable. Only javac's bridge methods connect those to the
 * {@code BotContext} methods the cards actually declare, so these are checked here rather than
 * trusted.
 */
class StepBridgeTest {

    @Test
    void aTaskThatOnlyWritesOnTickIsTickedThroughTheStepItIs() {
        List<String> calls = new ArrayList<>();
        StatusText line = new StatusText().set("lune.status.task_runner.finished");
        Task task = new Task() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public boolean automaticSkillLearning() {
                return false;
            }

            @Override
            public void onStart(BotContext ctx) {
                calls.add("start");
            }

            @Override
            public TaskStatus onTick(BotContext ctx) {
                calls.add("tick");
                return TaskStatus.SUCCESS;
            }

            @Override
            public void onStop(BotContext ctx) {
                calls.add("stop");
            }

            @Override
            public StatusText statusLine() {
                return line;
            }

            @Override
            public boolean holdsControls() {
                return false;
            }
        };
        Step<BotContext> step = task;
        step.start(null);
        assertEquals(TaskStatus.SUCCESS, step.tick(null));
        step.stop(null);
        assertEquals(List.of("start", "tick", "stop"), calls);
        assertSame(line, step.statusLine());
        assertFalse(step.holdsControls());
        assertTrue(step.madeProgress());
    }

    @Test
    void aTaskThatWritesTickItselfIsStillReached() {
        Task task = new Task() {
            @Override
            public String name() {
                return "own tick";
            }

            @Override
            public TaskStatus tick(BotContext ctx) {
                return TaskStatus.FAILED;
            }

            @Override
            public TaskStatus onTick(BotContext ctx) {
                return TaskStatus.SUCCESS;
            }
        };
        Step<BotContext> step = task;
        assertEquals(TaskStatus.FAILED, step.tick(null));
    }

    @Test
    void aWhileMonitorAnswersAsAGuard() {
        boolean[] released = {false};
        WhileMonitor monitor = new WhileMonitor() {
            @Override
            public String name() {
                return "monitor";
            }

            @Override
            public boolean shouldTakeControl(BotContext ctx) {
                return true;
            }

            @Override
            public void onControlReleased(BotContext ctx) {
                released[0] = true;
            }

            @Override
            public TaskStatus onTick(BotContext ctx) {
                return TaskStatus.RUNNING;
            }
        };
        Guard<BotContext> guard = monitor;
        assertTrue(guard.shouldTakeControl(null));
        assertEquals(TaskStatus.RUNNING, guard.tick(null));
        guard.onControlReleased(null);
        assertTrue(released[0]);
    }
}
