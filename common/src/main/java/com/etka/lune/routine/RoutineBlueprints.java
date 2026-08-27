package com.etka.lune.routine;

import com.etka.lune.bot.command.CommandRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-built task "puzzle pieces" a player can drop into the Task tab with one click.
 * <p>
 * Each blueprint is a tiny {@link Routine} with all its nodes preconfigured; inserting it just
 * copies the nodes into the routine being edited.
 */
public final class RoutineBlueprints {

    private static final Map<String, Routine> BLUEPRINTS = new LinkedHashMap<>();

    static {
        BLUEPRINTS.put("Diamond prospect",
                routine("Diamond prospect",
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:diamond_ore")),
                        node("find", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "prospect", "true")),
                        node("mine", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "limit", "0",
                                "auto_tool", "true", "prospect", "false"))));

        BLUEPRINTS.put("Find & mine diamond",
                routine("Find & mine diamond",
                        node("find", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "prospect", "true")),
                        node("mine", Map.of("targets", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "radius", "64", "y_min", "-64", "y_max", "16", "limit", "0",
                                "auto_tool", "true", "prospect", "false"))));

        BLUEPRINTS.put("Stripmine diamond level",
                routine("Stripmine diamond level",
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:diamond_ore")),
                        node("stripmine", Map.of(
                                "target", "minecraft:diamond_ore,minecraft:deepslate_diamond_ore",
                                "y_level", "-59", "branch_length", "32",
                                "spacing", "3", "branches", "8"))));

        BLUEPRINTS.put("Chop wood",
                routine("Chop wood",
                        node("chop", Map.of("radius", "64", "limit", "16"))));

        BLUEPRINTS.put("Harvest crops",
                routine("Harvest crops",
                        node("harvest", Map.of("targets", "minecraft:wheat,minecraft:carrots,minecraft:potatoes,minecraft:beetroots",
                                "radius", "32", "limit", "0"))));

        BLUEPRINTS.put("Hunt zombies",
                routine("Hunt zombies",
                        node("kill", Map.of("targets", "minecraft:zombie", "radius", "24"))));

        BLUEPRINTS.put("Get iron pick",
                routine("Get iron pick",
                        node("gettool", Map.of("tool", CommandRegistry.TOOL_FROM_BLOCK,
                                "target", "minecraft:iron_ore"))));

        // The tool and material names are the ones ToolCatalog offers in the Get Tools picker.
        BLUEPRINTS.put("Chop wood with an axe",
                routine("Chop wood with an axe",
                        node("gettool", Map.of("tool", "Axe", "material", "Stone")),
                        node("chop", Map.of("radius", "64", "limit", "16"))));
    }

    public static Map<String, Routine> all() {
        return BLUEPRINTS;
    }

    private static Routine routine(String name, RoutineNode... nodes) {
        Routine routine = new Routine();
        routine.name = name;
        for (RoutineNode node : nodes) {
            routine.nodes.add(node);
        }
        return routine;
    }

    private static RoutineNode node(String commandId, Map<String, String> params) {
        RoutineNode node = new RoutineNode(commandId);
        node.params.putAll(params);
        return node;
    }

    private RoutineBlueprints() {}
}
