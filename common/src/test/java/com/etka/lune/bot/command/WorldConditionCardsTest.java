package com.etka.lune.bot.command;

import com.etka.lune.bot.task.ConditionTask;
import com.etka.lune.bot.util.Weather;
import com.etka.lune.bot.util.WorldDimension;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two conditions whose answer is a name rather than a number: Check Weather and Check
 * Dimension.
 *
 * <p>Both could have been readings on the Check Player card, and both would have been unreadable
 * there: "Weather at most 2" compares correctly and tells the person reading the card nothing. So
 * they take the shape Check Time already has - one dropdown, and the Success and Fail edges as the
 * two answers.</p>
 */
class WorldConditionCardsTest {

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
    void bothCardsAskOneQuestionAndSitWithTheOtherConditions() {
        assertEquals(List.of("weather"), card("check_weather").params().stream().map(Param::id).toList());
        assertEquals(List.of("dimension"), card("check_dimension").params().stream().map(Param::id).toList());
        assertEquals("Logic & Conditions", CommandRegistry.categoryFor("check_weather"));
        assertEquals("Logic & Conditions", CommandRegistry.categoryFor("check_dimension"));
        assertEquals(Weather.labels(), ((Param.Choice) card("check_weather").params().get(0)).options());
        assertEquals(WorldDimension.labels(),
                ((Param.Choice) card("check_dimension").params().get(0)).options());
    }

    @Test
    void bothCardsBuildTheConditionTheyClaimTo() {
        assertInstanceOf(ConditionTask.class,
                card("check_weather").buildWith(Map.of("weather", "Raining")));
        assertInstanceOf(ConditionTask.class,
                card("check_dimension").buildWith(Map.of("dimension", "Nether")));
    }

    @Test
    void eachCardSpellsOutTheBranchItTakes() {
        assertEquals("If the weather is Thundering: Success; otherwise: Fail.",
                with("check_weather", Map.of("weather", "Thundering"), CommandDef::logicDescription));
        assertEquals("If Lune is in the Nether: Success; otherwise: Fail.",
                with("check_dimension", Map.of("dimension", "Nether"), CommandDef::logicDescription));
        assertEquals("If Lune is in the End: Success; otherwise: Fail.",
                with("check_dimension", Map.of("dimension", "End"), CommandDef::logicDescription));
    }

    @Test
    void everyOfferedNameReadsBackToItself() {
        for (String name : Weather.labels()) {
            assertEquals(name, Weather.fromLabel(name).label());
        }
        for (String name : WorldDimension.labels()) {
            assertEquals(name, WorldDimension.fromLabel(name).label());
        }
        assertNull(Weather.fromLabel("Drizzle"));
        assertNull(WorldDimension.fromLabel("Twilight Forest"));
        assertNull(Weather.fromLabel(null));
        assertNull(WorldDimension.fromLabel(null));
    }

    /**
     * It is raining during a thunderstorm, and the card says so. A job that wants the storm and
     * not the drizzle asks for Thundering; one that wants any wet sky asks for Raining and gets
     * both.
     */
    @Test
    void rainIsTrueDuringAThunderstormAndClearIsNot() {
        assertTrue(Weather.CLEAR.matches(false, false));
        assertFalse(Weather.RAIN.matches(false, false));
        assertFalse(Weather.THUNDER.matches(false, false));

        assertFalse(Weather.CLEAR.matches(true, false));
        assertTrue(Weather.RAIN.matches(true, false));
        assertFalse(Weather.THUNDER.matches(true, false));

        assertFalse(Weather.CLEAR.matches(true, true));
        assertTrue(Weather.RAIN.matches(true, true));
        assertTrue(Weather.THUNDER.matches(true, true));
    }

    /** What a status line names: the strongest of the three that is true. */
    @Test
    void theSkyIsNamedByTheWorstOfWhatItIsDoing() {
        assertEquals(Weather.CLEAR, Weather.of(false, false));
        assertEquals(Weather.RAIN, Weather.of(true, false));
        assertEquals(Weather.THUNDER, Weather.of(true, true));
    }

    @Test
    void eachNameMatchesItsOwnWorldAndNoOther() {
        assertTrue(WorldDimension.OVERWORLD.matches(Level.OVERWORLD));
        assertTrue(WorldDimension.NETHER.matches(Level.NETHER));
        assertTrue(WorldDimension.END.matches(Level.END));
        assertFalse(WorldDimension.OVERWORLD.matches(Level.NETHER));
        assertFalse(WorldDimension.END.matches(Level.NETHER));
        assertEquals(WorldDimension.NETHER, WorldDimension.of(Level.NETHER));
    }

    /**
     * A pack's own world is none of the three, and the card says Fail rather than picking the
     * closest. "Am I still at home?" is the Overworld option's Fail edge, which is the question
     * anybody wiring this up in a modpack is actually asking.
     */
    @Test
    void aDimensionAnotherModAddedIsNoneOfTheThree() {
        ResourceKey<Level> modded = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("twilightforest", "twilight_forest"));
        assertNull(WorldDimension.of(modded));
        for (WorldDimension dimension : WorldDimension.values()) {
            assertFalse(dimension.matches(modded), dimension.label() + " should not match a mod's own world");
        }
    }
}
