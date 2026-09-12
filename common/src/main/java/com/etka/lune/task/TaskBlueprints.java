package com.etka.lune.task;

import com.etka.lune.util.Lang;
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

    /**
     * Built when asked rather than at class-load.
     *
     * <p>A blueprint is named through the language file, and a static initialiser runs before the
     * game has one - the names would freeze as whatever was readable at load, and never change
     * again when the player switches language.</p>
     */
    private static Map<String, TaskGraph> build() {
        Map<String, TaskGraph> BLUEPRINTS = new LinkedHashMap<>();
        BLUEPRINTS.put(Lang.get("lune.blueprint.diamond_prospect"),
                task(Lang.get("lune.blueprint.diamond_prospect"),
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:diamond_ore")),
                        node("find", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "prospect", "true")),
                        node("mine", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "limit", "0",
                                "auto_tool", "true", "prospect", "false"))));

        BLUEPRINTS.put(Lang.get("lune.blueprint.find_mine_diamond"),
                task(Lang.get("lune.blueprint.find_mine_diamond"),
                        node("find", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "prospect", "true")),
                        node("mine", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "limit", "0",
                                "auto_tool", "true", "prospect", "false"))));

        BLUEPRINTS.put(Lang.get("lune.blueprint.stripmine_diamond_level"),
                task(Lang.get("lune.blueprint.stripmine_diamond_level"),
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:diamond_ore")),
                        node("stripmine", Map.of(
                                "target", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "y_level", "-59", "branch_length", "32",
                                "spacing", "3", "branches", "8"))));

        BLUEPRINTS.put(Lang.get("lune.blueprint.chop_wood"),
                task(Lang.get("lune.blueprint.chop_wood"),
                        node("chop", Map.of("radius", "64", "limit", "16"))));

        BLUEPRINTS.put(Lang.get("lune.blueprint.harvest_crops"),
                task(Lang.get("lune.blueprint.harvest_crops"),
                        node("harvest", Map.of("targets", "minecraft:wheat,minecraft:carrots,minecraft:potatoes,minecraft:beetroots",
                                "radius", "32", "limit", "0"))));

        BLUEPRINTS.put(Lang.get("lune.blueprint.hunt_zombies"),
                task(Lang.get("lune.blueprint.hunt_zombies"),
                        node("kill", Map.of("targets", "minecraft:zombie", "radius", "24"))));

        BLUEPRINTS.put(Lang.get("lune.blueprint.get_iron_pick"),
                task(Lang.get("lune.blueprint.get_iron_pick"),
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:iron_ore"))));

        // The tool and material names are the ones ToolCatalog offers in the Get Tools picker.
        BLUEPRINTS.put(Lang.get("lune.blueprint.chop_wood_with_axe"),
                task(Lang.get("lune.blueprint.chop_wood_with_axe"),
                        node("gettool", Map.of("tool", "Axe", "material", "Stone")),
                        node("chop", Map.of("radius", "64", "limit", "16"))));
        return BLUEPRINTS;
    }

    public static Map<String, TaskGraph> all() {
        return build();
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
