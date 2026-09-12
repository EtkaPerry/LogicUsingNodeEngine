package com.etka.lune.bot.command;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.task.StepPolicy;
import com.etka.lune.bot.task.StepTask;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Step card: three rows, and a description that leads with the turning it does not do. */
class StepCommandTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CommandDef card() {
        CommandDef def = CommandRegistry.byId("step");
        assertNotNull(def, "the Step card is not registered");
        return def;
    }

    @Test
    void theCardAsksWhichWayAndHowFar() {
        assertEquals(List.of("side", "blocks", "careful"),
                card().params().stream().map(Param::id).toList());
        assertEquals("Movement", CommandRegistry.categoryFor("step"));
    }

    @Test
    void everySideBuildsAStep() {
        for (StepPolicy.Side side : StepPolicy.Side.values()) {
            Task task = card().buildWith(Map.of("side", side.label(), "blocks", "2"));
            assertInstanceOf(StepTask.class, task, side.label());
            assertEquals(2, task.progress().target());
            assertTrue(task.name().toLowerCase().contains(side.label().toLowerCase()), task.name());
        }
    }

    /** One block is the point of the card, so that is what it opens on. */
    @Test
    void theCardOpensOnOneCarefulBlockToTheRight() {
        CommandDef def = card();
        assertEquals(StepPolicy.Side.RIGHT.label(), def.choiceValue("side"));
        assertEquals(1, def.intValue("blocks"));
        assertTrue(def.boolValue("careful"));
    }

    @Test
    void theCardExplainsThatItDoesNotTurn() {
        CommandDef def = card();
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(Map.of("side", StepPolicy.Side.RIGHT.label(), "blocks", "1", "careful", "true"));
            String logic = def.logicDescription();
            assertTrue(logic.contains("without turning"), logic);
            assertTrue(logic.contains("1 block right"), logic);
            assertTrue(logic.contains("sneaking"), logic);

            def.apply(Map.of("blocks", "3", "careful", "false"));
            assertTrue(def.logicDescription().contains("3 blocks"), def.logicDescription());
            assertTrue(def.logicDescription().contains("walking"), def.logicDescription());
        } finally {
            def.apply(saved);
        }
    }
}
