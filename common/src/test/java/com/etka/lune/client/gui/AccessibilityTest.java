package com.etka.lune.client.gui;

import com.etka.lune.config.BotConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The promise the accessibility settings make, tested apart from the client that draws them.
 *
 * <p>What matters here is not which hex value comes out but that the two palettes keep their
 * distinctions: a colour-blind-safe mode whose colours collide, or one that silently drops the
 * glyph, is worse than none - it looks like the setting is working.</p>
 */
class AccessibilityTest {

    private static final Accessibility.Mark[] SIGNALS = {
            Accessibility.Mark.GOOD, Accessibility.Mark.WARN, Accessibility.Mark.BAD};

    @Test
    void everyMarkIsADifferentColourInBothPalettes() {
        for (boolean safe : new boolean[] {false, true}) {
            Set<Integer> seen = new LinkedHashSet<>();
            for (Accessibility.Mark mark : Accessibility.Mark.values()) {
                assertTrue(seen.add(Accessibility.colour(mark, safe)),
                        "two marks share a colour with colourBlindSafe=" + safe + ": " + mark);
            }
        }
    }

    /**
     * The pair the whole setting exists for.
     *
     * <p>Green/amber is what the dashboard leans on hardest and what red-green colour blindness
     * flattens. In the safe palette the two must differ in lightness as well as hue, so they
     * survive being seen in greyscale.</p>
     */
    @Test
    void theSafePaletteSeparatesGoodFromWarnByLightnessToo() {
        int good = Accessibility.colour(Accessibility.Mark.GOOD, true);
        int warn = Accessibility.colour(Accessibility.Mark.WARN, true);
        assertNotEquals(good, warn);
        assertTrue(Math.abs(luminance(good) - luminance(warn)) > 0.15,
                "sky blue and yellow should not read as the same grey");
        int bad = Accessibility.colour(Accessibility.Mark.BAD, true);
        assertTrue(Math.abs(luminance(good) - luminance(bad)) > 0.15,
                "sky blue and vermillion should not read as the same grey");
    }

    @Test
    void theSafePaletteIsActuallyADifferentPalette() {
        for (Accessibility.Mark mark : SIGNALS) {
            assertNotEquals(Accessibility.colour(mark, false), Accessibility.colour(mark, true),
                    mark + " is the same colour in both palettes, so the setting does nothing");
        }
    }

    @Test
    void theGlyphOnlyAppearsWhenItWasAskedFor() {
        assertEquals("92%", Accessibility.marked("92%", Accessibility.Mark.WARN, false));
        String marked = Accessibility.marked("92%", Accessibility.Mark.WARN, true);
        assertTrue(marked.endsWith("92%"), marked);
        assertTrue(marked.startsWith(Accessibility.Mark.WARN.glyph()), marked);
    }

    /** Leading, so the ellipsis that trims a long row cannot take the glyph with it. */
    @Test
    void theGlyphLeadsRatherThanTrails() {
        String marked = Accessibility.marked("a very long value indeed", Accessibility.Mark.BAD, true);
        assertTrue(marked.indexOf(Accessibility.Mark.BAD.glyph()) == 0, marked);
    }

    @Test
    void everyMarkHasItsOwnGlyph() {
        Set<String> glyphs = new LinkedHashSet<>();
        for (Accessibility.Mark mark : Accessibility.Mark.values()) {
            assertFalse(mark.glyph().isBlank(), mark + " has no glyph");
            assertTrue(glyphs.add(mark.glyph()), "two marks share the glyph " + mark.glyph());
        }
    }

    /**
     * The four text sizes have to be four distinct steps, in order.
     *
     * <p>Two settings that resolve to the same scale are a control that does nothing on one of its
     * positions, which reads as the mod ignoring you.</p>
     */
    @Test
    void textSizesAreDistinctAndOrdered() {
        int previous = Integer.MIN_VALUE;
        for (String size : BotConfig.TEXT_SIZES) {
            int step = BotConfig.textSizeStep(size);
            assertTrue(step > previous, size + " does not step past the size below it");
            previous = step;
        }
        assertEquals(0, BotConfig.textSizeStep(BotConfig.TEXT_SIZE_NORMAL),
                "Normal is the size the layout asked for, so it must not shift it");
        // A config hand-edited to something meaningless reads as Normal rather than throwing.
        assertEquals(0, BotConfig.textSizeStep("nonsense"));
        assertEquals(0, BotConfig.textSizeStep(null));
    }

    /** Relative luminance, for the greyscale check above. */
    private static double luminance(int argb) {
        return 0.2126 * channel(argb >> 16) + 0.7152 * channel(argb >> 8) + 0.0722 * channel(argb);
    }

    private static double channel(int shifted) {
        double value = (shifted & 0xFF) / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
