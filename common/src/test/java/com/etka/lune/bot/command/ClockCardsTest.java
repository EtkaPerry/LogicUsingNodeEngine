package com.etka.lune.bot.command;

import com.etka.lune.bot.Task;
import com.etka.lune.bot.task.ConditionTask;
import com.etka.lune.bot.task.CountdownTask;
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

/**
 * The two cards that answer "how long" and "what time is it": Countdown and Check Clock.
 *
 * <p>Both exist because the palette could say "wait five seconds" and "is it dark?" and nothing
 * in between. A job asked to run until eight in the evening had no card to ask, and a wait longer
 * than an hour could not be written down at all.</p>
 */
class ClockCardsTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static CommandDef card(String id) {
        CommandDef def = CommandRegistry.byId(id);
        assertNotNull(def, "the " + id + " card is not registered");
        return def;
    }

    /** Reads a card with the given settings and puts back whatever the palette was holding. */
    private static <T> T with(String id, Map<String, String> values, Function<CommandDef, T> read) {
        CommandDef def = card(id);
        Map<String, String> saved = def.snapshot();
        try {
            def.apply(values);
            return read.apply(def);
        } finally {
            def.apply(saved);
        }
    }

    @Test
    void countdownAsksHowLongAndInWhat() {
        assertEquals(List.of("amount", "unit"),
                card("countdown").params().stream().map(Param::id).toList());
        assertEquals("Logic & Conditions", CommandRegistry.categoryFor("countdown"));
        assertEquals(CountdownTask.Unit.labels(),
                ((Param.Choice) card("countdown").params().get(1)).options());
    }

    @Test
    void checkClockAsksWhichClockWhichRuleAndWhatTime() {
        assertEquals(List.of("clock", "comparison", "hour", "minute"),
                card("check_clock").params().stream().map(Param::id).toList());
        assertEquals("Logic & Conditions", CommandRegistry.categoryFor("check_clock"));
    }

    /** The example the card was written for: twenty minutes, five hours, two days. */
    @Test
    void aCountdownCanBeWrittenInWhicheverUnitSuitsTheWait() {
        assertEquals(20 * 60L, totalSeconds("20", "Minutes"));
        assertEquals(5 * 3_600L, totalSeconds("5", "Hours"));
        assertEquals(2 * 86_400L, totalSeconds("2", "Days"));
        assertEquals(45L, totalSeconds("45", "Seconds"));
    }

    private static long totalSeconds(String amount, String unit) {
        Task built = card("countdown").buildWith(Map.of("amount", amount, "unit", unit));
        return assertInstanceOf(CountdownTask.class, built).totalSeconds();
    }

    /** A stored unit nobody recognises must not silently become a different length of wait. */
    @Test
    void anUnknownUnitReadsAsMinutes() {
        assertEquals(CountdownTask.Unit.MINUTES, CountdownTask.Unit.fromLabel("Fortnights"));
        assertEquals(CountdownTask.Unit.MINUTES, CountdownTask.Unit.fromLabel(null));
        for (String label : CountdownTask.Unit.labels()) {
            assertEquals(label, CountdownTask.Unit.fromLabel(label).label());
        }
    }

    @Test
    void theCountdownCardSaysHowLongAndThatItIsRealTime() {
        assertEquals("Lune waits 5h 0m 0s of real time, then gives Success.",
                with("countdown", Map.of("amount", "5", "unit", "Hours"),
                        CommandDef::logicDescription));
        assertEquals("Lune waits 2d 0h 0m of real time, then gives Success.",
                with("countdown", Map.of("amount", "2", "unit", "Days"),
                        CommandDef::logicDescription));
        assertEquals("Lune waits 20m 0s of real time 3 times over, then gives Success.",
                with("countdown", Map.of("amount", "20", "unit", "Minutes"),
                        def -> def.logicDescription(3)));
        assertEquals("Lune counts down 20m 0s of real time, then starts again.",
                with("countdown", Map.of("amount", "20", "unit", "Minutes"),
                        def -> def.logicDescription(0)));
    }

    /**
     * The two rules get a sentence each rather than one frame with a word dropped in, so this
     * checks both actually exist and are not the same line twice.
     */
    @Test
    void theClockCardSpellsOutWhichClockAndWhichWayRound() {
        assertEquals("If the System clock still reads before 20:00: Success; otherwise: Fail.",
                with("check_clock", Map.of("clock", "System clock", "comparison", "Before",
                        "hour", "20", "minute", "0"), CommandDef::logicDescription));
        assertEquals("If the Game clock has reached 06:30: Success; otherwise: Fail.",
                with("check_clock", Map.of("clock", "Game clock", "comparison", "At or after",
                        "hour", "6", "minute", "30"), CommandDef::logicDescription));
    }

    @Test
    void bothCardsBuildTheTaskTheyClaimTo() {
        assertInstanceOf(CountdownTask.class,
                card("countdown").buildWith(Map.of("amount", "1", "unit", "Seconds")));
        assertInstanceOf(ConditionTask.class,
                card("check_clock").buildWith(Map.of("clock", "System clock",
                        "comparison", "Before", "hour", "20", "minute", "0")));
    }
}
