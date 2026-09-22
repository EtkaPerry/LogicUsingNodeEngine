package com.etka.lune.task;

import com.etka.lune.bot.command.CommandDef;
import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.bot.command.Param;
import com.etka.lune.bot.task.FailTask;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the seeded jobs against the live command registry rather than against a restatement of it.
 *
 * <p>The graph tests in {@link DefaultTasksTest} prove the wiring; nothing there notices that a
 * card asks for material "Wood" when the picker offers "Wooden", or sets {@code min_y} on a command
 * whose parameter is {@code y_min}. Both would load, both would silently fall back to a default,
 * and the job would quietly do the wrong work. Registries need a one-off bootstrap of about five
 * seconds, which is why this lives beside the structural suite instead of inside it.</p>
 */
class DefaultTasksParameterTest {

    /**
     * Choices whose options come from what the player has saved, not from a fixed list. A task
     * that names a job or a waypoint is deliberately allowed to name one that does not exist yet,
     * and reaching for the stores that hold them would drag config files into a unit test.
     */
    private static final Set<String> PLAYER_SUPPLIED = Set.of("task", "waypoint", "check_distance");

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everySeededCardNamesARealCommand() {
        forEachCommandNode((task, node) ->
                assertNotNull(CommandRegistry.byId(node.commandId),
                        task.name + ": " + node.id + " uses unknown command " + node.commandId));
    }

    @Test
    void everySeededParameterIsOneItsCommandDeclares() {
        forEachCommandNode((task, node) -> {
            CommandDef def = CommandRegistry.byId(node.commandId);
            if (def == null) {
                return;
            }
            Set<String> declared = def.params().stream().map(Param::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String key : node.params.keySet()) {
                assertTrue(declared.contains(key),
                        task.name + ": " + node.id + " sets '" + key + "', which " + node.commandId
                                + " does not have. It has " + declared);
            }
        });
    }

    /**
     * A value that will not survive a round trip through its own serialiser is a value the job does
     * not actually have: every parameter type is documented as tolerating junk by dropping it, so a
     * typo reads back as the command's default rather than as an error.
     */
    @Test
    void everySeededValueSurvivesItsOwnSerialiser() {
        forEachCommandNode((task, node) -> {
            CommandDef def = CommandRegistry.byId(node.commandId);
            if (def == null) {
                return;
            }
            // The definition doubles as the palette's live editing state, so put it back.
            Map<String, String> saved = def.snapshot();
            try {
                for (Map.Entry<String, String> entry : node.params.entrySet()) {
                    Param<?> param = param(def, entry.getKey());
                    if (param == null || param instanceof Param.Choice) {
                        continue;
                    }
                    param.deserialize(entry.getValue());
                    assertEquals(entry.getValue(), param.serialize(),
                            task.name + ": " + node.id + "." + entry.getKey() + " does not read "
                                    + "back as it was written - an unknown id was dropped, or a "
                                    + "number was clamped into range");
                }
            } finally {
                def.apply(saved);
            }
        });
    }

    @Test
    void everySeededChoiceIsOneThePickerOffers() {
        forEachCommandNode((task, node) -> {
            CommandDef def = CommandRegistry.byId(node.commandId);
            if (def == null || PLAYER_SUPPLIED.contains(node.commandId)) {
                return;
            }
            for (Map.Entry<String, String> entry : node.params.entrySet()) {
                if (!(param(def, entry.getKey()) instanceof Param.Choice choice)) {
                    continue;
                }
                assertTrue(choice.options().contains(entry.getValue()),
                        task.name + ": " + node.id + "." + entry.getKey() + " is '"
                                + entry.getValue() + "', not one of " + choice.options());
            }
        });
    }

    /**
     * Tags resolve from a datapack at runtime, so an unknown one is kept rather than dropped and
     * the round-trip check cannot see it. Vanilla's own tag list can, and a seeded job has no
     * business naming a tag vanilla does not ship.
     */
    @Test
    void everySeededBlockTagIsOneVanillaShips() {
        Set<String> vanilla = vanillaBlockTagIds();
        assertFalse(vanilla.isEmpty(), "reflection over BlockTags found nothing to compare against");
        forEachCommandNode((task, node) -> {
            CommandDef def = CommandRegistry.byId(node.commandId);
            if (def == null) {
                return;
            }
            for (Map.Entry<String, String> entry : node.params.entrySet()) {
                if (!(param(def, entry.getKey()) instanceof Param.BlockSet)) {
                    continue;
                }
                for (String part : entry.getValue().split(",")) {
                    if (part.startsWith("#")) {
                        assertTrue(vanilla.contains(part.substring(1)),
                                task.name + ": " + node.id + "." + entry.getKey() + " asks for "
                                        + part + ", which vanilla does not define");
                    }
                }
            }
        });
    }

    /** Every card builds the work it names, rather than the placeholder a bad parameter produces. */
    @Test
    void everySeededCardBuildsRealWork() {
        forEachCommandNode((task, node) -> {
            CommandDef def = CommandRegistry.byId(node.commandId);
            if (def == null || PLAYER_SUPPLIED.contains(node.commandId)) {
                return;
            }
            assertFalse(def.buildWith(node.params) instanceof FailTask,
                    task.name + ": " + node.id + " builds a Fail placeholder, so one of its "
                            + "parameters is not the value the command expected");
        });
    }

    /** Every seeded card that runs a command, with the editor-only pulse and source cards skipped. */
    private static void forEachCommandNode(java.util.function.BiConsumer<TaskGraph, TaskNode> check) {
        for (TaskGraph task : DefaultTasks.create()) {
            for (TaskNode node : task.nodes) {
                if (!node.isSourceNode() && !node.isPulseNode()) {
                    check.accept(task, node);
                }
            }
        }
    }

    private static Param<?> param(CommandDef def, String id) {
        return def.params().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    /**
     * Every block tag vanilla names in code. 26.2 moved the tags a block shares with its item onto
     * {@code BlockItemTags}, and {@code BlockTags} re-exposes only some of them (the ore tags, for
     * one, it does not), so that class is read as well wherever it exists; 26.1.2 has no such class.
     */
    private static Set<String> vanillaBlockTagIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Field field : staticFields(BlockTags.class)) {
            if (TagKey.class.isAssignableFrom(field.getType())) {
                ids.add(((TagKey<?>) read(field)).location().toString());
            }
        }
        try {
            Class<?> blockItemTags = Class.forName("net.minecraft.tags.BlockItemTags");
            for (Field field : staticFields(blockItemTags)) {
                Object id = read(field);
                if (id != null && id.getClass().getMethod("block").invoke(id) instanceof TagKey<?> tag) {
                    ids.add(tag.location().toString());
                }
            }
        } catch (ClassNotFoundException before26_2) {
            // Every block tag is on BlockTags here.
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read BlockItemTags", e);
        }
        return ids;
    }

    private static List<Field> staticFields(Class<?> holder) {
        return Arrays.stream(holder.getDeclaredFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .toList();
    }

    private static Object read(Field field) {
        try {
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read " + field.getDeclaringClass().getSimpleName()
                    + "." + field.getName(), e);
        }
    }
}
