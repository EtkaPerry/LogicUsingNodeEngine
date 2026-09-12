package com.etka.lune.bot.command;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.task.FailTask;
import com.etka.lune.bot.task.PlaceBlockTask;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Place Block card. Where the block goes is the only decision it makes, so that is what these
 * pin down: a spot that follows the bot needs nothing else answered, and a fixed one is refused
 * until it has actually been given.
 */
class PlaceBlockCommandTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CommandDef card() {
        CommandDef def = CommandRegistry.byId("place");
        assertNotNull(def, "the Place Block card is not registered");
        return def;
    }

    @Test
    void theCardAsksWhatAndWhere() {
        assertEquals(List.of("blocks", "where", "target", "waypoint"),
                card().params().stream().map(Param::id).toList());
        assertEquals(List.of("In front", "Under me", "Above me", "Coordinates", "Waypoint"),
                PlaceBlockTask.Where.labels());
        assertEquals("Mining & Building", CommandRegistry.categoryFor("place"));
    }

    /** The three relative spots are the ones that compose with Walk, so they need nothing else. */
    @Test
    void aRelativeSpotNeedsNothingElseAnswered() {
        for (PlaceBlockTask.Where where : PlaceBlockTask.Where.values()) {
            if (!where.isRelative()) {
                continue;
            }
            Task task = card().buildWith(Map.of(
                    "where", where.label(),
                    "blocks", "minecraft:cobblestone"));
            assertInstanceOf(PlaceBlockTask.class, task, where.label() + " should build real work");
        }
    }

    /**
     * The waypoint half is not built here: resolving a waypoint reads the store, which is scoped to
     * the world the client has open, and a headless test has neither. It is the same reason the
     * seeded-task suite skips every card that names a waypoint.
     */
    @Test
    void coordinatesAreRefusedUntilTheyAreGiven() {
        assertInstanceOf(FailTask.class, card().buildWith(Map.of(
                        "where", PlaceBlockTask.Where.COORDINATES.label(),
                        "blocks", "minecraft:cobblestone",
                        "target", "")),
                "coordinates that were never set are not a place to build");
    }

    @Test
    void aCardWithNoBlockChosenSaysSo() {
        assertInstanceOf(FailTask.class, card().buildWith(Map.of(
                "where", PlaceBlockTask.Where.IN_FRONT.label(),
                "blocks", "")));
    }

    /** Only the row the chosen spot is read from is shown. */
    @Test
    void theCardShowsOnlyTheRowItReads() {
        CommandDef def = card();
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(Map.of("where", PlaceBlockTask.Where.IN_FRONT.label()));
            assertFalse(def.isRelevant("target"));
            assertFalse(def.isRelevant("waypoint"));
            assertTrue(def.isRelevant("blocks"));

            def.apply(Map.of("where", PlaceBlockTask.Where.COORDINATES.label()));
            assertTrue(def.isRelevant("target"));
            assertFalse(def.isRelevant("waypoint"));

            def.apply(Map.of("where", PlaceBlockTask.Where.WAYPOINT.label()));
            assertFalse(def.isRelevant("target"));
            assertTrue(def.isRelevant("waypoint"));
        } finally {
            def.apply(saved);
        }
    }

    @Test
    void theCardExplainsWhereTheBlockGoes() {
        CommandDef def = card();
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(Map.of("where", PlaceBlockTask.Where.IN_FRONT.label(),
                    "blocks", "minecraft:cobblestone"));
            assertTrue(def.logicDescription().contains("in front of it"), def.logicDescription());

            def.apply(Map.of("where", PlaceBlockTask.Where.COORDINATES.label(), "target", "10 64 -20"));
            assertTrue(def.logicDescription().contains("10, 64, -20"), def.logicDescription());
            assertTrue(def.logicDescription().contains("walking there first"), def.logicDescription());
        } finally {
            def.apply(saved);
        }
    }

    @Test
    void anUnknownSpotFallsBackToTheOneThatAlwaysWorks() {
        assertEquals(PlaceBlockTask.Where.IN_FRONT, PlaceBlockTask.Where.fromLabel("nonsense"));
        assertEquals(PlaceBlockTask.Where.UNDER, PlaceBlockTask.Where.fromLabel("under me"));
    }
}
