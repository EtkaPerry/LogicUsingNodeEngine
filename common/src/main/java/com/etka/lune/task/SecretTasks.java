package com.etka.lune.task;

import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** An opt-in playground: the top loop runs; individual gameplay trials have manual buttons. */
public final class SecretTasks {
    public static final String NAME = "Secret: Everything Lab";
    public static final String HELPER_NAME = "Secret: Lab Subtask";
    private static final String MARKER = "secret_everything_start";
    private static final String HELPER_MARKER = "secret_subtask_start";

    private SecretTasks() {}

    /** Preserve edited/renamed copies and never add duplicates on another unlock. */
    public static TaskGraph restoreInto(List<TaskGraph> tasks) {
        TaskGraph helper = find(tasks, HELPER_MARKER, HELPER_NAME);
        if (helper == null) {
            helper = helper();
            tasks.add(helper);
        }
        TaskGraph main = find(tasks, MARKER, NAME);
        if (main == null) {
            main = create(helper.name);
            tasks.add(main);
        }
        return main;
    }

    private static TaskGraph find(List<TaskGraph> tasks, String marker, String name) {
        return tasks.stream().filter(task -> task.nodeById(marker) != null
                || name.equalsIgnoreCase(task.name)).findFirst().orElse(null);
    }

    public static TaskGraph helper() {
        TaskGraph graph = new TaskGraph(HELPER_NAME);
        TaskNode start = node(graph, HELPER_MARKER, "start", 0, 0);
        TaskNode timer = node(graph, "subtask_timer", "timer", 1, 0);
        timer.params.put("seconds", "1");
        TaskNode end = node(graph, "subtask_end", "end", 2, 0);
        start.onSuccess = timer.id;
        signal(timer, end, 0);
        return graph;
    }

    public static TaskGraph create(String helperName) {
        TaskGraph graph = new TaskGraph(NAME);
        TaskNode start = node(graph, MARKER, "start", 0, 0);
        TaskNode healthy = check(graph, "healthy", "Health", "At least", "0", 1);
        TaskNode impossibleHealth = check(graph, "impossible_health", "Health", "Less than", "0", 2);
        TaskNode air = check(graph, "air_check", "Air", "At most", "300", 3);
        TaskNode daylight = node(graph, "daylight", "check_time", 4, 0);
        daylight.params.put("phase", "Day");
        TaskNode timer = node(graph, "loop_timer", "timer", 5, 0);
        timer.params.put("seconds", "2");
        start.onSuccess = healthy.id;
        both(healthy, impossibleHealth);
        both(impossibleHealth, air);
        both(air, daylight);
        both(daylight, timer);
        signal(timer, healthy, 0);
        graph.cableAnchors.put(TaskCableAnchor.key("signal", timer.id, "0", healthy.id, "-1"),
                TaskCableRoute.returning(TaskCanvas.outputX(timer), timer.editorY + 38,
                        TaskCanvas.inputX(healthy), TaskCanvas.inputY(healthy), 130, 0));
        healthy.exposedOutputs.add("threshold");
        impossibleHealth.exposedInputs.add("threshold");
        impossibleHealth.inputLinks.put("threshold", new TaskDataLink(healthy.id, "threshold"));

        TaskNode guard = node(graph, "loop_guard", "self_preservation", 3, 1);
        for (TaskNode check : List.of(healthy, impossibleHealth, air, daylight)) {
            check.whileVisible = true;
            check.onWhile = guard.id;
        }

        TaskNode always = node(graph, "continuous", "always", 0, 2);
        TaskNode counter = node(graph, "continuous_counter", "counter", 1, 2);
        counter.params.put("count", "10");
        TaskNode counterEnd = node(graph, "continuous_end", "end", 2, 2);
        always.alwaysTargets.add(counter.id);
        signal(counter, counterEnd, 0);

        TaskNode pulse = node(graph, "metronome", "pulse", 0, 3);
        pulse.alwaysIntervalSeconds = 3;
        TaskNode relay = node(graph, "fanout", "signal_relay", 1, 3);
        relay.signalInputCount = 2;
        relay.signalOutputCount = 2;
        pulse.alwaysTargets.add(relay.id);
        TaskNode button = node(graph, "manual_fanout", "button", 0, 4);
        button.signalLinks.add(new TaskSignalLink(0, relay.id, 1));
        for (int port = 0; port < 2; port++) {
            TaskNode delay = node(graph, "fanout_delay_" + port, "timer", 2, 3 + port);
            delay.params.put("seconds", "" + (port + 1));
            TaskNode end = node(graph, "fanout_end_" + port, "end", 3, 3 + port);
            signal(relay, delay, port);
            signal(delay, end, 0);
        }
        TaskNode observer = node(graph, "watch_check", "observer", 4, 2);
        observer.observedNodeId = healthy.id;
        TaskNode observedEnd = node(graph, "observer_end", "end", 5, 2);
        signal(observer, observedEnd, 0);

        // Every real command has an isolated manual trial, including long jobs and session endings.
        // Explicit End nodes prevent legacy list fallthrough from launching the next trial.
        int trial = 0;
        for (CommandDef def : CommandRegistry.all()) {
            TaskNode probe = new TaskNode(def.id());
            if (probe.isSourceNode() || probe.isPulseNode()) continue;
            int col = (trial % 2) * 3;
            int row = 6 + trial / 2;
            TaskNode trigger = node(graph, "try_" + def.id(), "button", col, row);
            TaskNode command = node(graph, "command_" + def.id(), def.id(), col + 1, row);
            command.params.putAll(def.snapshot());
            configureTrial(command, helperName);
            TaskNode end = node(graph, "done_" + def.id(), "end", col + 2, row);
            signal(trigger, command, 0);
            both(command, end);
            trial++;
        }
        return graph;
    }

    private static void configureTrial(TaskNode node, String helperName) {
        // Keep configurable work quotas small. Commands without quotas retain their real behavior.
        for (String key : Set.of("limit", "count", "length", "distance", "attempts", "step", "branches")) {
            if (node.params.containsKey(key)) node.params.put(key, "1");
        }
        if (node.params.containsKey("radius")) node.params.put("radius", "8");
        switch (node.commandId) {
            case "task" -> node.params.put("name", helperName);
            case "stop_game" -> node.params.put("ending", "Return to main menu");
            case "sleep" -> node.params.put("wait_for_night", "false");
            case "fish" -> node.params.put("auto_recast", "false");
            case "find", "mine" -> node.params.put("prospect", "false");
            case "stripmine" -> node.params.put("branch_length", "4");
            case "save_waypoint" -> node.params.putAll(Map.of("action", "Save here", "name", "Secret Lab"));
            case "waypoint" -> node.params.put("name", "Secret Lab");
            case "check_distance" -> node.params.put("waypoint", "Secret Lab");
            case "craft" -> node.params.put("recipe", com.etka.lune.bot.catalog.CraftRecipe
                    .ofItem(net.minecraft.world.item.Items.OAK_PLANKS).serialize());
            default -> { }
        }
    }

    private static TaskNode check(TaskGraph graph, String id, String metric, String comparison, String threshold, int column) {
        TaskNode node = node(graph, id, "check_player", column, 0);
        node.params.putAll(Map.of("metric", metric, "comparison", comparison, "threshold", threshold));
        return node;
    }

    private static TaskNode node(TaskGraph graph, String id, String command, int column, int row) {
        TaskNode node = new TaskNode(command);
        node.id = id;
        node.editorX = 24 + column * 220;
        node.editorY = 26 + row * 180;
        graph.nodes.add(node);
        return node;
    }

    private static void both(TaskNode from, TaskNode to) {
        from.onSuccess = to.id;
        from.onFailure = to.id;
    }

    private static void signal(TaskNode from, TaskNode to, int output) {
        from.signalLinks.add(new TaskSignalLink(output, to.id, to.isSignalRelayNode() ? 0 : -1));
    }
}
