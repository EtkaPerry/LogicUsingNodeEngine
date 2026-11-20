package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.widget.PanelTour.Stop;
import com.etka.lune.util.LuneLanguages;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Lune says on the tour, in every language Lune ships: there, and short enough for the card.
 *
 * <p>The card grows with its words rather than cutting them off, so a long stop is never lost - but
 * a stop that takes eight lines at the card's narrowest is a wall of text beside the thing it is
 * meant to point at, and on a small screen a card that tall has nowhere to stand clear of it.</p>
 */
class TourWordsTest {

    private static final Path LANG = Path.of("src/main/resources/assets/lune/lang");
    /**
     * A generous glyph width, as the starter notes are measured: most letters are six pixels with
     * their gap, spaces four, the narrow ones less. Words that fit here fit on screen.
     */
    private static final int GLYPH = 6;
    /** The most lines a stop's words may take at the card's narrowest. */
    private static final int MOST_LINES = 7;

    private static List<String> shippedLanguages() throws IOException {
        try (Stream<Path> files = Files.list(LANG)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> tourKeys() {
        List<String> keys = new ArrayList<>(List.of("lune.tour.offer.title", "lune.tour.offer.text",
                "lune.tour.offer.later", "lune.tour.offer.start", "lune.tour.offer.skip",
                "lune.tour.skip", "lune.tour.training", "lune.tour.title", "lune.tour.caption",
                "lune.tour.page.intro", "lune.tour.page.start", "lune.mascot.corner.tour"));
        for (Stop stop : Stop.values()) {
            keys.add(stop.titleKey());
            keys.add(stop.textKey());
        }
        return keys;
    }

    @Test
    void everyLanguageLuneShipsSaysEveryLineOfTheTour() throws IOException {
        Map<String, String> english = LuneLanguages.load(LuneLanguages.ENGLISH);
        for (String language : shippedLanguages()) {
            Map<String, String> lines = LuneLanguages.load(language);
            for (String key : tourKeys()) {
                String line = lines.get(key);
                assertTrue(line != null && !line.isBlank(), language + " has nothing for " + key);
                if (!language.equals(LuneLanguages.ENGLISH) && key.startsWith("lune.tour.")
                        && key.endsWith(".text")) {
                    assertNotEquals(english.get(key), line, language + " left " + key + " in English");
                }
            }
        }
    }

    @Test
    void everyStopFitsItsCardAtItsNarrowest() throws IOException {
        int perLine = (TourOverlay.CARD_NARROW - TourOverlay.PAD * 2) / GLYPH;
        for (String language : shippedLanguages()) {
            Map<String, String> lines = LuneLanguages.load(language);
            for (Stop stop : Stop.values()) {
                int taken = wrappedLines(lines.get(stop.textKey()), perLine);
                assertTrue(taken <= MOST_LINES, language + ": " + stop + " takes " + taken
                        + " lines at the narrowest card, more than " + MOST_LINES);
                assertTrue(taken < TourOverlay.MAX_LINES, language + ": " + stop + " is cut short");
            }
        }
    }

    @Test
    void everyStopsNameFitsBesideLunesFaceAtTheNarrowestCard() throws IOException {
        int room = TourOverlay.CARD_NARROW - TourOverlay.PAD * 2 - TourOverlay.HEAD
                - TourOverlay.TITLE_GAP;
        for (String language : shippedLanguages()) {
            Map<String, String> lines = LuneLanguages.load(language);
            for (Stop stop : Stop.values()) {
                String title = lines.get(stop.titleKey());
                assertFalse(title.length() * GLYPH > room, language + ": " + stop + "'s name '"
                        + title + "' is cut short at the narrowest card");
            }
        }
    }

    /** Greedy word wrap at a fixed character width: how many lines the text takes. */
    private static int wrappedLines(String text, int perLine) {
        int lines = 1;
        int used = 0;
        for (String word : text.split(" ")) {
            int length = word.length();
            if (used == 0) {
                used = length;
            } else if (used + 1 + length <= perLine) {
                used += 1 + length;
            } else {
                lines++;
                used = length;
            }
            while (used > perLine) {
                lines++;
                used -= perLine;
            }
        }
        return lines;
    }
}
