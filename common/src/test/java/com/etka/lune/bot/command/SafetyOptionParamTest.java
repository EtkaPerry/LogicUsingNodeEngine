package com.etka.lune.bot.command;

import com.etka.lune.task.TaskSafety;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Self Preservation card's tactic switches: that they stick, and that adding them did not change
 * what an existing card does.
 *
 * <p>The second half is the one worth a test. These parameters did not exist when the tasks on
 * people's disks were saved, and a card whose file has no line for "Water MLG" must come back on
 * rather than come back off - {@code CommandDef.apply} is what guarantees that, by leaving an absent
 * parameter at its declared default, and it would be very easy to break while adding the next one.
 */
class SafetyOptionParamTest {

    private static final Set<String> TACTICS = Set.of(
            "clutch_water", "clutch_boat", "clutch_cushion", "protect_fireballs", "build_cover");
    /** Dangers added to the card after tasks had already been saved with it. */
    private static final Set<String> LATER_DANGERS = Set.of("protect_fire");

    private static CommandDef card() {
        return CommandRegistry.byId(TaskSafety.COMMAND_ID);
    }

    @AfterEach
    void putTheCardBack() {
        // The definition doubles as the palette's live editing state, so a test that flips a switch
        // and walks away has flipped it for every test after it.
        card().apply(Map.of());
        for (Param<?> param : card().params()) {
            if (TACTICS.contains(param.id()) || LATER_DANGERS.contains(param.id())) {
                param.deserialize("true");
            }
        }
    }

    @Test
    void everyTacticIsOnUntilSomebodyTurnsItOff() {
        CommandDef def = card();
        Set<String> declared = def.params().stream().map(Param::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String tactic : TACTICS) {
            assertTrue(declared.contains(tactic), "the card lost its " + tactic + " switch");
            assertEquals("true", def.snapshot().get(tactic), tactic + " does not default to on");
        }
    }

    @Test
    void aCardSavedBeforeTheseExistedKeepsAllOfThem() {
        CommandDef def = card();
        // Exactly the parameters the card had before the tactic switches were added.
        Map<String, String> old = Map.of(
                "protect_air", "true", "air_compare", "At most", "air_value", "120",
                "protect_lava", "true", "protect_fall", "true", "fall_threshold", "10",
                "protect_monsters", "true", "monster_compare", "At most", "monster_distance", "8",
                "protect_health", "true");
        def.apply(old);

        for (String tactic : TACTICS) {
            assertEquals("true", def.snapshot().get(tactic),
                    tactic + " came back off for a card that has never heard of it");
        }
    }

    @Test
    void aCardSavedBeforeFireWasADangerPutsFiresOut() {
        CommandDef def = card();
        // Every guard already on somebody's canvas has no line for fire, and the point of adding it
        // was for those cards to start putting fires out - so a card that never heard of it is on.
        def.apply(Map.of(
                "protect_air", "true", "protect_lava", "true", "protect_fall", "true",
                "protect_monsters", "true", "protect_health", "true"));
        assertEquals("true", def.snapshot().get("protect_fire"),
                "a card saved before fire was a danger came back ignoring it");

        def.apply(Map.of("protect_fire", "false"));
        Map<String, String> saved = def.snapshot();
        assertEquals("false", saved.get("protect_fire"), "turning fire off does not stick");
        def.apply(Map.of("protect_fire", "true"));
        def.apply(saved);
        assertFalse(def.boolValue("protect_fire"));
    }

    @Test
    void aTurnedOffTacticSurvivesTheRoundTripToDisk() {
        CommandDef def = card();
        def.apply(Map.of("clutch_water", "false", "protect_fireballs", "false"));

        Map<String, String> saved = def.snapshot();
        assertEquals("false", saved.get("clutch_water"));
        assertEquals("false", saved.get("protect_fireballs"));
        // The ones nobody touched are untouched.
        assertEquals("true", saved.get("clutch_boat"));
        assertEquals("true", saved.get("build_cover"));

        // And reading the file back gives the same card, which is the whole point of "it saves".
        def.apply(Map.of());
        def.apply(saved);
        assertFalse(def.boolValue("clutch_water"));
        assertFalse(def.boolValue("protect_fireballs"));
        assertTrue(def.boolValue("clutch_boat"));
    }

    @Test
    void aTacticRowIsHiddenWhileItsDangerIsSwitchedOff() {
        CommandDef def = card();
        def.apply(Map.of("protect_fall", "false", "protect_monsters", "false"));
        assertFalse(def.isRelevant("clutch_water"), "asking how to clutch a fall nobody watches for");
        assertFalse(def.isRelevant("clutch_boat"));
        assertFalse(def.isRelevant("clutch_cushion"));
        assertFalse(def.isRelevant("protect_fireballs"));
        assertFalse(def.isRelevant("build_cover"));
        // The threshold rows that were always there are not affected by this.
        assertTrue(def.isRelevant("fall_threshold"));

        def.apply(Map.of("protect_fall", "true", "protect_monsters", "true"));
        assertTrue(def.isRelevant("clutch_water"));
        assertTrue(def.isRelevant("protect_fireballs"));
    }

    @Test
    void theSuggestedCardCarriesEveryParameterTheCommandDeclares() {
        // TaskSafety is what the editor and the mascot offer when a task has no guard. A tactic
        // missing from that map is a switch the offered card cannot be configured with.
        Set<String> declared = card().params().stream().map(Param::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> offered = TaskSafety.defaultParams();
        for (String id : declared) {
            assertTrue(offered.containsKey(id),
                    "TaskSafety does not set '" + id + "', so the offered guard cannot hold it");
        }
    }
}
