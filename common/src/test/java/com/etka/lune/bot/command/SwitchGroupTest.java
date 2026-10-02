package com.etka.lune.bot.command;

import com.etka.lune.compat.Mobs;
import com.etka.lune.task.TaskSafety;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only and All: one click keeps one of a card's switches on and the others beside it off, and the
 * same click on the one left on turns them all back on.
 *
 * <p>The case it was asked for is a task with two guards in it, one that only watches the air and
 * one that only watches health. Before this, each was five switches turned off by hand in a pane
 * that scrolls, with the Health rows of the air guard still asking to be filled in.</p>
 */
class SwitchGroupTest {

    private static final List<String> DANGERS = List.of(
            "protect_air", "protect_lava", "protect_fire", "protect_fall", "protect_monsters",
            "protect_health");
    private static final List<String> CLUTCHES = List.of("clutch_water", "clutch_boat", "clutch_cushion");
    private static final List<String> MOB_TACTICS = List.of("protect_fireballs", "build_cover");

    /** Every definition doubles as the palette's live editing state; each test puts it back. */
    private final Map<String, Map<String, String>> saved = new HashMap<>();

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void remember() {
        for (CommandDef def : CommandRegistry.all()) {
            saved.put(def.id(), def.snapshot());
        }
        guard().apply(TaskSafety.defaultParams());
    }

    @AfterEach
    void putBack() {
        for (CommandDef def : CommandRegistry.all()) {
            def.apply(saved.get(def.id()));
        }
    }

    private static CommandDef guard() {
        return CommandRegistry.byId(TaskSafety.COMMAND_ID);
    }

    private static List<String> ids(List<Param.Bool> switches) {
        return switches.stream().map(Param::id).toList();
    }

    private static List<String> on(CommandDef def, List<String> switches) {
        return switches.stream().filter(def::boolValue).toList();
    }

    @Test
    void theDangersAreOneGroupAndEachDangersTacticsAnother() {
        CommandDef def = guard();
        for (String danger : DANGERS) {
            assertEquals(DANGERS, ids(def.switchGroup(danger)), danger);
        }
        for (String clutch : CLUTCHES) {
            assertEquals(CLUTCHES, ids(def.switchGroup(clutch)), clutch);
        }
        for (String tactic : MOB_TACTICS) {
            assertEquals(MOB_TACTICS, ids(def.switchGroup(tactic)), tactic);
        }
        assertTrue(def.switchGroup("air_value").isEmpty(), "a number is not a switch");
        assertTrue(def.switchGroup("health_compare").isEmpty(), "a rule is not a switch");
    }

    @Test
    void onlyDrowningIsAGuardThatWatchesTheAirAndNothingElse() {
        CommandDef def = guard();
        assertTrue(def.keepOnly("protect_air"));

        assertEquals(List.of("protect_air"), on(def, DANGERS));
        assertTrue(def.isOnlySwitchOn("protect_air"));
        assertFalse(def.isOnlySwitchOn("protect_lava"), "a switch that is off is not on alone");
        // The tactics are a group of their own, and nobody asked for them to change.
        assertEquals(CLUTCHES, on(def, CLUTCHES));
        assertEquals(MOB_TACTICS, on(def, MOB_TACTICS));

        // What is left on screen is what the card will read: Drowning and its two rows, and the
        // five dangers that are off, to turn back on.
        List<String> shown = new ArrayList<>();
        for (Param<?> param : def.params()) {
            if (def.isRelevant(param.id())) {
                shown.add(param.id());
            }
        }
        assertEquals(List.of("protect_air", "air_compare", "air_value", "protect_lava",
                "protect_fire", "protect_fall", "protect_monsters", "protect_health"), shown);
    }

    @Test
    void theSameClickOnTheOneLeftOnTurnsThemAllBackOn() {
        CommandDef def = guard();
        def.apply(Map.of("clutch_boat", "false"));
        def.keepOnly("protect_air");

        assertTrue(def.keepOnly("protect_air"));
        assertEquals(DANGERS, on(def, DANGERS));
        assertFalse(def.isOnlySwitchOn("protect_air"));
        assertFalse(def.boolValue("clutch_boat"), "a clutch turned off before stays off after");
    }

    @Test
    void aSecondDangerIsAnOrdinaryClickAndOnlyStillWorksFromThere() {
        CommandDef def = guard();
        def.keepOnly("protect_air");
        def.apply(Map.of("protect_health", "true"));

        assertEquals(List.of("protect_air", "protect_health"), on(def, DANGERS));
        assertFalse(def.isOnlySwitchOn("protect_air"), "two on is not one on alone");

        // The other guard of the pair: only health, from wherever the card was left.
        assertTrue(def.keepOnly("protect_health"));
        assertEquals(List.of("protect_health"), on(def, DANGERS));
        assertTrue(def.isRelevant("health_value"));
        assertFalse(def.isRelevant("air_value"));
    }

    @Test
    void onlyAmongTheClutchesLeavesTheDangersAlone() {
        CommandDef def = guard();
        assertTrue(def.keepOnly("clutch_boat"));
        assertEquals(List.of("clutch_boat"), on(def, CLUTCHES));
        assertEquals(DANGERS, on(def, DANGERS));
    }

    @Test
    void aSwitchThatIsNotOnScreenIsNeitherOfferedNorChanged() {
        CommandDef def = guard();
        def.apply(Map.of("protect_fall", "false"));
        assertTrue(def.switchGroup("clutch_water").isEmpty());
        assertFalse(def.keepOnly("clutch_water"));
        assertEquals(CLUTCHES, on(def, CLUTCHES));
    }

    @Test
    void aSwitchWithNothingBesideItHasNothingToKeepApartFrom() {
        // Boat has one switch: whether to take the boat back afterwards.
        CommandDef boat = CommandRegistry.byId("boat");
        assertTrue(boat.switchGroup("reclaim").isEmpty());
        boolean before = boat.boolValue("reclaim");
        assertFalse(boat.keepOnly("reclaim"));
        assertFalse(boat.isOnlySwitchOn("reclaim"));
        assertEquals(before, boat.boolValue("reclaim"));
    }

    /**
     * Any card with more than one switch, not just the guard. A card added next month with two
     * switches gets Only without anybody remembering to ask for it.
     */
    @Test
    void everyCardWithSeveralSwitchesOffersOnlyOnEachOfThem() {
        int cards = 0;
        for (CommandDef def : CommandRegistry.all()) {
            Map<String, String> before = def.snapshot();
            List<String> own = new ArrayList<>();
            for (Param<?> param : def.params()) {
                if (param instanceof Param.Bool && param.parentSwitch() == null
                        && def.isRelevant(param.id())) {
                    own.add(param.id());
                }
            }
            if (own.size() < 2) {
                continue;
            }
            cards++;
            Map<String, String> allOn = new HashMap<>();
            own.forEach(id -> allOn.put(id, "true"));
            for (String id : own) {
                assertEquals(own, ids(def.switchGroup(id)), def.id() + "." + id);
                // From all on, so Only has something to switch off: a card that starts with one
                // switch on alone, as Hunt Endermen does with its shield, would read All instead.
                def.apply(allOn);
                def.keepOnly(id);
                assertEquals(List.of(id), on(def, own), def.id() + ": Only on " + id);
                assertTrue(def.isOnlySwitchOn(id));
                def.keepOnly(id);
                assertEquals(own, on(def, own), def.id() + ": All from " + id);
                def.apply(before);
            }
        }
        assertTrue(cards >= 5, "Kill, Mine, Harvest and the rest have several switches each");
    }

    @Test
    void aRowCanOnlySitUnderASwitchDeclaredBeforeIt() {
        assertThrows(IllegalArgumentException.class, () -> new CommandDef("test_card", List.of(
                new Param.Ints("count", 1, 0, 9).under("enabled"),
                new Param.Bool("enabled", true)), def -> null));
        assertThrows(IllegalArgumentException.class, () -> new CommandDef("test_card", List.of(
                new Param.Ints("count", 1, 0, 9),
                new Param.Ints("limit", 1, 0, 9).under("count")), def -> null));

        CommandDef fine = new CommandDef("test_card", List.of(
                new Param.Bool("enabled", true),
                new Param.Ints("count", 1, 0, 9).under("enabled")), def -> null);
        assertTrue(fine.isRelevant("count"));
        fine.apply(Map.of("enabled", "false"));
        assertFalse(fine.isRelevant("count"));
    }

    @Test
    void aMobListKeepsJustOneAndHasNoAll() {
        Param.EntitySet targets = null;
        for (Param<?> param : CommandRegistry.byId("kill").params()) {
            if (param instanceof Param.EntitySet set) {
                targets = set;
            }
        }
        assertTrue(targets != null, "Kill has a mob list");
        targets.set(new java.util.LinkedHashSet<>(List.of(Mobs.ZOMBIE, Mobs.SKELETON, Mobs.SPIDER)));
        assertFalse(targets.isOnly(Mobs.SKELETON));

        targets.keepOnly(Mobs.SKELETON);
        assertEquals(Set.of(Mobs.SKELETON), targets.get());
        assertTrue(targets.isOnly(Mobs.SKELETON));
        // Kept again it stays one mob: every mob in the list would include the villagers.
        targets.keepOnly(Mobs.SKELETON);
        assertEquals(Set.of(Mobs.SKELETON), targets.get());
    }
}
