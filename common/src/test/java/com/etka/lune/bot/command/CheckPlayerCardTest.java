package com.etka.lune.bot.command;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.task.ConditionTask;
import com.etka.lune.bot.util.PlayerMetric;
import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Check Player card, which asks for a number and compares it.
 *
 * <p>It offered health, hunger and air for a long time, and the conditionals people reach for
 * were the ones beside those: room for what is about to be mined, a pickaxe about to break, light
 * dark enough for something to spawn in. The list and the reading are one thing now, so an option
 * the card cannot read cannot be offered.</p>
 */
class CheckPlayerCardTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CommandDef card() {
        CommandDef def = CommandRegistry.byId("check_player");
        assertNotNull(def, "the check_player card is not registered");
        return def;
    }

    /** Reads the card with the given settings and puts back whatever the palette was holding. */
    private static <T> T with(Map<String, String> values, Function<CommandDef, T> read) {
        CommandDef def = card();
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(values);
            return read.apply(def);
        } finally {
            def.apply(saved);
        }
    }

    @Test
    void theDropdownOffersEveryReadingTheCardCanTake() {
        assertEquals(List.of("metric", "comparison", "threshold"),
                card().params().stream().map(Param::id).toList());
        assertEquals(PlayerMetric.labels(),
                ((Param.Choice) card().params().get(0)).options());
        assertEquals("Logic & Conditions", CommandRegistry.categoryFor("check_player"));
    }

    /**
     * The three it always had are still spelled the way saved tasks spell them. These are
     * identifiers on disk: renaming one silently turns every card holding it into an unknown
     * reading.
     */
    @Test
    void theReadingsThatWereAlreadySavedAreUnchanged() {
        assertTrue(PlayerMetric.labels().containsAll(List.of("Health", "Hunger", "Air")));
        assertEquals("Health", ((Param.Choice) card().params().get(0)).get(),
                "a fresh card should still start on Health");
    }

    /**
     * Air maxes out at 300 and used to be the largest thing this box held. An XP level and a
     * distance to another player both go further, and a threshold that cannot be typed is a
     * comparison nobody can write.
     */
    @Test
    void theThresholdReachesPastWhatAirNeeded() {
        Param.Ints threshold = (Param.Ints) card().params().get(2);
        assertEquals(0, threshold.min());
        assertTrue(threshold.max() >= 9999, "threshold stops at " + threshold.max());
    }

    @Test
    void theCardSpellsOutTheBranchItTakes() {
        assertEquals("If Health is at most 8: Success; otherwise: Fail.",
                with(Map.of("metric", "Health", "comparison", "At most", "threshold", "8"),
                        CommandDef::logicDescription));
        assertEquals("If Free slots is at most 2: Success; otherwise: Fail.",
                with(Map.of("metric", "Free slots", "comparison", "At most", "threshold", "2"),
                        CommandDef::logicDescription));
        assertEquals("If Tool durability is at most 15: Success; otherwise: Fail.",
                with(Map.of("metric", "Tool durability", "comparison", "At most", "threshold", "15"),
                        CommandDef::logicDescription));
    }

    @Test
    void everyReadingBuildsACondition() {
        for (String metric : PlayerMetric.labels()) {
            assertInstanceOf(ConditionTask.class, card().buildWith(
                    Map.of("metric", metric, "comparison", "At most", "threshold", "8")),
                    metric + " should build a condition");
        }
    }

    /**
     * The name is for reading and the identifier is for comparing, and a condition used to hand
     * out the same translated string for both. A Turkish player's run journal said
     * {@code taskid=Oyuncuyu Denetle}, which no harness script matches and which shares no row
     * with the English profile the same job builds.
     */
    @Test
    void theIdentifierStaysEnglishWhileTheNameIsTranslated() {
        Lang.select("tr_tr");
        try {
            Task player = card().buildWith(
                    Map.of("metric", "Health", "comparison", "At most", "threshold", "8"));
            assertEquals("Oyuncuyu Denetle", player.name());
            assertEquals("Check Player", player.learningId());

            Task weather = CommandRegistry.byId("check_weather")
                    .buildWith(Map.of("weather", "Raining"));
            assertEquals("Hava Durumunu Denetle", weather.name());
            assertEquals("Check Weather", weather.learningId());
        } finally {
            Lang.select(LuneLanguages.GAME_DEFAULT);
        }
    }
}
