package com.etka.lune.training;

import com.etka.lune.bot.command.CommandRegistry;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskNode;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the lessons against the live command registry rather than against a restatement of it.
 *
 * <p>{@link TrainingCourseTest} proves the graphs; nothing there notices that a decoy names
 * {@code select_items} when the registry offers {@code select_item}. A card id that resolves to
 * nothing is not a compile error and not a crash - the palette silently offers two tiles instead of
 * three, and the puzzle quietly stops being the one that was written. The registry needs a one-off
 * bootstrap of a few seconds, which is why this is split out of the structural suite.</p>
 */
class TrainingCardsTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyOfferedCardIsOneThePaletteCanBuild() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            for (String id : lesson.choices()) {
                assertTrue(exists(id), lesson.id() + " offers '" + id
                        + "', which is neither a general node nor a registered command");
            }
        }
    }

    @Test
    void everyCardAlreadyOnAStarterCanvasExists() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            TaskGraph attempt = lesson.newAttempt();
            for (TaskNode node : attempt.nodes) {
                assertTrue(exists(node.commandId), lesson.id() + " starts with a '"
                        + node.commandId + "' card, which no longer exists");
            }
        }
    }

    /**
     * Parameters written into a starter must be ones the card actually has.
     *
     * <p>A misspelt parameter loads, falls back to the command's default and does something
     * plausible but wrong - a Mine card told {@code auto_tool=false} through a key nobody reads
     * would quietly provision its own pickaxe, and the tool lesson would have no missing card
     * in it at all.</p>
     */
    @Test
    void starterParametersAreOnesTheirCardsAccept() {
        for (TrainingLesson lesson : TrainingCourse.lessons()) {
            for (TaskNode node : lesson.newAttempt().nodes) {
                var def = CommandRegistry.byId(node.commandId);
                if (def == null) {
                    continue;
                }
                for (String parameter : node.params.keySet()) {
                    assertTrue(def.snapshot().containsKey(parameter),
                            lesson.id() + " sets '" + parameter + "' on a " + node.commandId
                                    + " card, which has no such parameter");
                }
            }
        }
    }

    private static boolean exists(String id) {
        return id != null && (TrainingCourse.GENERAL_NODES.contains(id)
                || CommandRegistry.byId(id) != null);
    }
}
