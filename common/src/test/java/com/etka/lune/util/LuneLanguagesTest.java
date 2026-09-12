package com.etka.lune.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language Lune reads in, which is deliberately not the language the game is in.
 *
 * <p>The setting is one line in a config file and a dropdown, but the thing it must never do is
 * leave somebody looking at raw keys. A pinned language that is missing a line, or missing
 * entirely, has to fall through to English rather than to {@code lune.gui.tasks.run}.</p>
 */
class LuneLanguagesTest {

    @AfterEach
    void backToTheGameDefault() {
        // Global state: a test that pinned a language and walked away would pin it for the rest
        // of the suite.
        Lang.select(LuneLanguages.GAME_DEFAULT);
    }

    @Test
    void theListLeadsWithTheGameDefaultAndEnglish() {
        List<String> available = LuneLanguages.available();
        assertEquals(LuneLanguages.GAME_DEFAULT, available.get(0),
                "the default must be offered first; it is what a fresh install uses");
        assertEquals(LuneLanguages.ENGLISH, available.get(1));
        assertEquals(available.size(), available.stream().distinct().count(),
                "a language shipped in the jar and also written by the player is one entry");
    }

    @Test
    void pinningSomethingThatDoesNotExistStillReadsAsEnglish() {
        Lang.select("zz_zz");
        assertEquals("zz_zz", Lang.selected());
        assertEquals("Run", Lang.get("lune.gui.tasks.run"),
                "a language with no file behind it must fall through, not show its keys");
    }

    @Test
    void aBlankChoiceMeansTheGameDefault() {
        Lang.select("tr_tr");
        Lang.select(null);
        assertEquals(LuneLanguages.GAME_DEFAULT, Lang.selected());
        Lang.select("  ");
        assertEquals(LuneLanguages.GAME_DEFAULT, Lang.selected());
    }

    @Test
    void theGameDefaultIsNamedRatherThanShownAsACode() {
        assertEquals("Game default", LuneLanguages.displayName(LuneLanguages.GAME_DEFAULT));
        assertEquals("Game default", LuneLanguages.displayName(null));
        // An unknown code has no name to borrow, so it says what it is.
        assertEquals("zz_zz", LuneLanguages.displayName("zz_zz"));
    }

    @Test
    void pinningALanguageDoesNotBreakDialogueBanks() {
        Lang.select("zz_zz");
        List<String> idle = Lang.bank("lune.mascot.idle.day");
        assertTrue(idle.size() >= 2, "banks must survive a pinned language: " + idle);
        assertFalse(idle.contains("lune.mascot.idle.day.1"),
                "an unresolved key leaked into the bank: " + idle);
    }

    @Test
    void nothingIsLoadedForTheGameDefault() {
        assertTrue(LuneLanguages.load(LuneLanguages.GAME_DEFAULT).isEmpty());
        assertTrue(LuneLanguages.load(null).isEmpty());
    }
}
