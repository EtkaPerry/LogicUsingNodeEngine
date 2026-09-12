package com.etka.lune.bot.command;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.task.SaveWaypointTask;
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
 * The Save Waypoint card. The thing worth pinning down is which of its two name rows is in force:
 * a new place is typed, an existing one is chosen, and the card must never read the wrong one.
 */
class SaveWaypointCommandTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CommandDef card() {
        CommandDef def = CommandRegistry.byId("save_waypoint");
        assertNotNull(def, "the Save Waypoint card is not registered");
        return def;
    }

    @Test
    void theCardOffersSavingMovingAndRemoving() {
        assertEquals(List.of("action", "name", "waypoint"),
                card().params().stream().map(Param::id).toList());
        assertEquals(List.of("Save here", "Move here", "Remove"), SaveWaypointTask.Action.labels());
        assertEquals("Movement", CommandRegistry.categoryFor("save_waypoint"));
    }

    @Test
    void savingHereUsesTheTypedName() {
        Task task = card().buildWith(Map.of(
                "action", SaveWaypointTask.Action.ADD.label(),
                "name", "Base",
                "waypoint", "Somewhere else"));
        assertInstanceOf(SaveWaypointTask.class, task);
        assertEquals("Save waypoint Base", task.name(),
                "saving a new place must read the typed name, not the chosen one");
    }

    @Test
    void movingAndRemovingUseTheChosenWaypoint() {
        assertEquals("Move waypoint Base", card().buildWith(Map.of(
                "action", SaveWaypointTask.Action.MOVE.label(),
                "name", "Typed but not used",
                "waypoint", "Base")).name());
        assertEquals("Remove waypoint Base", card().buildWith(Map.of(
                "action", SaveWaypointTask.Action.REMOVE.label(),
                "name", "Typed but not used",
                "waypoint", "Base")).name());
    }

    /** Only one of the two name rows can be answered, so only one of them is shown. */
    @Test
    void theCardShowsOnlyTheRowItReads() {
        CommandDef def = card();
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(Map.of("action", SaveWaypointTask.Action.ADD.label()));
            assertTrue(def.isRelevant("name"));
            assertFalse(def.isRelevant("waypoint"));

            def.apply(Map.of("action", SaveWaypointTask.Action.REMOVE.label()));
            assertFalse(def.isRelevant("name"));
            assertTrue(def.isRelevant("waypoint"));
        } finally {
            def.apply(saved);
        }
    }

    /** A card explains itself from its values, including when they are not filled in yet. */
    @Test
    void theCardExplainsWhatItWillDo() {
        CommandDef def = card();
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(Map.of("action", SaveWaypointTask.Action.ADD.label(), "name", ""));
            assertTrue(def.logicDescription().contains("name it after a flower"),
                    "an unnamed card says what it will call the place: " + def.logicDescription());

            def.apply(Map.of("action", SaveWaypointTask.Action.ADD.label(), "name", "Base"));
            assertTrue(def.logicDescription().contains("'Base'"), def.logicDescription());

            def.apply(Map.of("action", SaveWaypointTask.Action.REMOVE.label(), "waypoint", "Base"));
            assertTrue(def.logicDescription().contains("forget the waypoint 'Base'"),
                    def.logicDescription());
        } finally {
            def.apply(saved);
        }
    }

    /** A name is typed by hand, so it has to survive the trip through the task file unchanged. */
    @Test
    void aTypedNameSurvivesBeingStored() {
        CommandDef def = card();
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(Map.of("name", "  Mine entrance  "));
            assertEquals("Mine entrance", def.textValue("name"), "surrounding spaces are dropped");
            assertEquals("Mine entrance", def.snapshot().get("name"));
        } finally {
            def.apply(saved);
        }
    }

    /** An unknown label is not a reason to refuse the card; it falls back to the safe action. */
    @Test
    void anUnknownActionFallsBackToSaving() {
        assertEquals(SaveWaypointTask.Action.ADD, SaveWaypointTask.Action.fromLabel("nonsense"));
        assertEquals(SaveWaypointTask.Action.REMOVE, SaveWaypointTask.Action.fromLabel("remove"));
    }
}
