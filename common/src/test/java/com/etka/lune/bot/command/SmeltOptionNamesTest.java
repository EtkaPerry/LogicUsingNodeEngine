package com.etka.lune.bot.command;

import com.etka.lune.util.Lang;
import com.etka.lune.util.LuneLanguages;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The smelting dropdown asks the game what its options are called.
 *
 * <p>Every option names a real item, and Minecraft already ships their names in every language
 * it supports. A second copy in Lune's own file is a second thing to translate, a second thing to
 * get wrong, and - for a player running a language nobody has translated Lune into - a dropdown
 * reading "Raw Iron" above a furnace that says "Rohes Eisen".</p>
 */
class SmeltOptionNamesTest {

    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static Param.Choice input() {
        return (Param.Choice) CommandRegistry.byId("smelt").params().get(0);
    }

    @Test
    void optionsAreNamedByTheGameNotByLunesLanguageFile() {
        Param.Choice input = input();
        assertTrue(input.labelsItsOwnOptions(),
                "the smelt input writes its own labels, so no lune.choice.* line is expected");

        for (String option : input.options()) {
            // Nothing in Lune's file answers to these any more; the label has to be coming from
            // the item, which is the whole point.
            String key = "lune.choice." + option.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "_");
            assertFalse(Lang.has(key), key + " should have been removed in favour of the item name");
        }

        assertEquals(List.of("Ancient Debris", "Cobblestone", "Raw Copper", "Raw Gold", "Raw Iron"),
                input.options().stream().map(input::label).sorted().toList());
    }

    @Test
    void theStoredValueIsStillTheEnglishKeyASavedTaskHolds() {
        Param.Choice input = input();
        input.set("Raw Gold");
        assertEquals("Raw Gold", input.serialize());
        input.set("Raw Iron");
    }

    /**
     * A line that points at a vanilla key reads as the game's word, not as the pointer.
     *
     * <p>The failure this guards is quiet: {@code Lang.get} hands back whatever it finds, so a
     * pointer that is not being resolved shows up on screen as {@code @item.minecraft.diamond} -
     * or, if the marker is stripped without a lookup, as {@code item.minecraft.diamond}, which
     * looks enough like a label to survive a glance.</p>
     */
    @Test
    void pointerLinesResolveToTheGamesOwnWord() {
        assertEquals("Diamond", Lang.get("lune.choice.diamond"));
        assertEquals("Bow", Lang.get("lune.choice.bow"));
        assertEquals("Map", Lang.get("lune.command.find_map.param.map.label"));

        // The mob hunts are not pointed: English wants "Hunt Blazes" and the game only has the
        // singular, while Turkish reads the same either way. They keep a line of their own.
        assertEquals("Endermen", Lang.get("lune.choice.endermen"));
        assertEquals("Blaze rods", Lang.get("lune.command.huntblazes.param.count.label"));

        // Pinning a language must not reintroduce the copy: Turkish has no line for these, and
        // the fallback to bundled English must not win over the game's Turkish either.
        Lang.select("tr_tr");
        try {
            assertFalse(Lang.get("lune.choice.diamond").startsWith("@"),
                    "the pointer leaked to the screen");
            assertFalse(Lang.get("lune.choice.diamond").startsWith("item."),
                    "the marker was stripped but nothing looked the key up");
            // The game is in English in a test, so this reads the English name - the point is that
            // it comes from the game rather than from a line Lune keeps of its own.
            assertEquals("Diamond", Lang.get("lune.choice.diamond"));
        } finally {
            Lang.select(LuneLanguages.GAME_DEFAULT);
        }
    }
}
