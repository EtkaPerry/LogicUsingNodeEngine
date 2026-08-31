package com.etka.lune.task;

import com.etka.lune.bot.command.CommandRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-built task "puzzle pieces" a player can drop into the Task tab with one click.
 * <p>
 * Each blueprint is a tiny {@link TaskGraph} with all its nodes preconfigured; inserting it just
 * copies the nodes into the task being edited.
 */
public final class TaskBlueprints {

    private static final Map<String, TaskGraph> BLUEPRINTS = new LinkedHashMap<>();

    static {
        BLUEPRINTS.put("Diamond prospect",
                task("Diamond prospect",
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:diamond_ore")),
                        node("find", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "prospect", "true")),
                        node("mine", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "limit", "0",
                                "auto_tool", "true", "prospect", "false"))));

        BLUEPRINTS.put("Find & mine diamond",
                task("Find & mine diamond",
                        node("find", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "prospect", "true")),
                        node("mine", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "limit", "0",
                                "auto_tool", "true", "prospect", "false"))));

        BLUEPRINTS.put("Stripmine diamond level",
                task("Stripmine diamond level",
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:diamond_ore")),
                        node("stripmine", Map.of(
                                "target", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "y_level", "-59", "branch_length", "32",
                                "spacing", "3", "branches", "8"))));

        BLUEPRINTS.put("Chop wood",
                task("Chop wood",
                        node("chop", Map.of("radius", "64", "limit", "16"))));

        BLUEPRINTS.put("Harvest crops",
                task("Harvest crops",
                        node("harvest", Map.of("targets", "minecraft:wheat,minecraft:carrots,minecraft:potatoes,minecraft:beetroots",
                                "radius", "32", "limit", "0"))));

        BLUEPRINTS.put("Hunt zombies",
                task("Hunt zombies",
                        node("kill", Map.of("targets", "minecraft:zombie", "radius", "24"))));

        BLUEPRINTS.put("Get iron pick",
                task("Get iron pick",
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:iron_ore"))));

        // The tool and material names are the ones ToolCatalog offers in the Get Tools picker.
        BLUEPRINTS.put("Chop wood with an axe",
                task("Chop wood with an axe",
                        node("gettool", Map.of("tool", "Axe", "material", "Stone")),
                        node("chop", Map.of("radius", "64", "limit", "16"))));
    }

    public static Map<String, TaskGraph> all() {
        return BLUEPRINTS;
    }

    private static TaskGraph task(String name, TaskNode... nodes) {
        TaskGraph task = new TaskGraph();
        task.name = name;
        for (TaskNode node : nodes) {
            task.nodes.add(node);
        }
        return task;
    }

    private static TaskNode node(String commandId, Map<String, String> params) {
        TaskNode node = new TaskNode(commandId);
        node.params.putAll(params);
        return node;
    }

    private TaskBlueprints() {}
}
