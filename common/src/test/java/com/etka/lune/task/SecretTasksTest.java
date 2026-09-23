package com.etka.lune.task;

import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.command.Param;
import com.google.gson.Gson;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SecretTasksTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tenthRapidPressUnlocksButSlowPressesDoNotAccumulate() {
        SecretTaskUnlock unlock = new SecretTaskUnlock();
        for (int i = 0; i < 9; i++) assertFalse(unlock.press(i * 100_000_000L));
        assertTrue(unlock.press(900_000_000L));
        assertFalse(unlock.press(1_000_000_000L));
        for (int i = 1; i < 20; i++) assertFalse(unlock.press(i * 6_000_000_000L));
    }

    @Test
    void entireGestureMustFitInsideFiveSeconds() {
        SecretTaskUnlock unlock = new SecretTaskUnlock();
        for (int i = 0; i < 9; i++) assertFalse(unlock.press(i * 500_000_000L));
        assertFalse(unlock.press(SecretTaskUnlock.WINDOW_NANOS + 1));
        for (int i = 1; i < 9; i++) assertFalse(unlock.press(SecretTaskUnlock.WINDOW_NANOS + 1 + i));
        assertTrue(unlock.press(SecretTaskUnlock.WINDOW_NANOS + 10));
    }

    @Test
    void hiddenUntilUnlockedAndPreservesRenamedEditedCopiesAcrossSerialization() {
        List<TaskGraph> tasks = new ArrayList<>(DefaultTasks.create());
        int shelf = tasks.size();
        assertTrue(tasks.stream().noneMatch(task -> task.name.equals(SecretTasks.NAME)));
        TaskGraph main = SecretTasks.restoreInto(tasks);
        assertEquals(shelf + 2, tasks.size());
        main.name = "My renamed lab";
        main.nodeById("healthy").params.put("threshold", "2");
        Gson gson = new Gson();
        List<TaskGraph> restored = new ArrayList<>(List.of(gson.fromJson(gson.toJson(tasks), TaskGraph[].class)));
        TaskGraph again = SecretTasks.restoreInto(restored);
        assertEquals(shelf + 2, restored.size());
        assertEquals("My renamed lab", again.name);
        assertEquals("2", again.nodeById("healthy").params.get("threshold"));
    }

    @Test
    void everyCommandAndSignalKindIsPresentAndReachable() {
        TaskGraph graph = SecretTasks.create(SecretTasks.HELPER_NAME);
        Set<String> commands = graph.nodes.stream().map(node -> node.commandId).collect(Collectors.toSet());
        assertTrue(commands.containsAll(CommandRegistry.all().stream().map(CommandDef::id).toList()));
        assertTrue(commands.containsAll(Set.of("start", "always", "pulse", "timer", "counter",
                "signal_relay", "button", "observer", "end")));
        for (TaskGraph task : List.of(graph, SecretTasks.helper())) {
            assertEquals(task.nodes.size(), new HashSet<>(task.nodes.stream().map(node -> node.id).toList()).size());
            assertTrue(TaskConnectionAudit.firstIssue(task).isEmpty(),
                    () -> TaskConnectionAudit.firstIssue(task).toString());
            assertTrue(task.nodes.stream().allMatch(node -> node.editorX != null && node.editorY != null));
        }
        assertFalse(graph.nodeById("impossible_health").inputLinks.isEmpty());
        assertTrue(graph.nodeById("healthy").whileVisible);
        assertEquals(2, graph.nodeById("fanout").signalOutputCount);
        assertEquals(2, graph.nodeById("fanout").signalInputCount);
    }

    @Test
    void allGameplayTrialsRequireTheirButtonAndBothOutcomesEndOnlyThatTrial() {
        TaskGraph graph = SecretTasks.create(SecretTasks.HELPER_NAME);
        for (TaskNode node : graph.nodes) {
            if (!node.id.startsWith("command_")) continue;
            TaskNode trigger = graph.nodeById("try_" + node.commandId);
            assertTrue(trigger.isButtonNode());
            assertEquals(node.id, trigger.signalLinks.getFirst().targetNodeId);
            assertEquals(node.onSuccess, node.onFailure);
            assertTrue(graph.nodeById(node.onSuccess).isEndNode());
            assertEquals(1, node.repeat);
            assertFalse(graph.nodes.stream().anyMatch(source -> source.alwaysTargets.contains(node.id)));
        }
        assertEquals(SecretTasks.HELPER_NAME, graph.nodeById("command_task").params.get("name"));
        assertEquals("Return to main menu", graph.nodeById("command_stop_game").params.get("ending"));
    }

    @Test
    void configuredParametersAreValidAndDoNotMutateThePalette() {
        Map<String, Map<String, String>> before = CommandRegistry.all().stream()
                .collect(Collectors.toMap(CommandDef::id, CommandDef::snapshot));
        TaskGraph graph = SecretTasks.create(SecretTasks.HELPER_NAME);
        for (CommandDef def : CommandRegistry.all()) assertEquals(before.get(def.id()), def.snapshot());
        for (TaskNode node : graph.nodes) {
            CommandDef def = CommandRegistry.byId(node.commandId);
            if (def == null) continue; // START, Always, Pulse and Relay are editor primitives.
            Map<String, String> saved = def.snapshot();
            try {
                for (var entry : node.params.entrySet()) {
                    Param<?> param = def.params().stream().filter(p -> p.id().equals(entry.getKey())).findFirst().orElseThrow();
                    param.deserialize(entry.getValue());
                    assertEquals(entry.getValue(), param.serialize(), node.id + "." + entry.getKey());
                }
            } finally {
                def.apply(saved);
            }
        }
    }
}
