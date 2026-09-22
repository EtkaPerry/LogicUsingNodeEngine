package com.etka.lune.client.gui;

import com.etka.lune.config.BotConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menu scale is a promise to two people at once: the player whose screen cannot fit the layout,
 * and the player who chose a large GUI scale because they need one. These pin down both halves.
 */
class UiScaleTest {

    private static final String AUTO = BotConfig.LUNE_UI_AUTO;

    @Test
    void shrinksTheDefaultScaleUntilTheLayoutFits() {
        // 1080p resolves "Auto" to 4, which leaves the tabs 480x270 - less than they are drawn for.
        assertEquals(3, UiScale.menuScale(4, 1920, 1080, AUTO));
    }

    @Test
    void stopsAtTheFirstScaleThatFits() {
        // 3 already gives 640x360, so nothing licenses going further down to 2.
        assertTrue(1920 / 3 >= 620 && 1080 / 3 >= 330);
        assertEquals(3, UiScale.menuScale(4, 1920, 1080, AUTO));
        // A screen that is roomy at the player's own scale is left completely alone.
        assertEquals(2, UiScale.menuScale(2, 1920, 1080, AUTO));
        assertEquals(6, UiScale.menuScale(6, 3840, 2160, AUTO));
    }

    @Test
    void neverShrinksPastHalfTheChosenScale() {
        // A deliberately huge interface on a small screen: the layout would want scale 1, but a
        // scale picked for a TV or for poor eyesight must stay recognisably large.
        assertEquals(4, UiScale.menuScale(8, 1280, 720, AUTO));
        assertEquals(1, UiScale.menuScale(1, 640, 480, AUTO));
    }

    @Test
    void matchGameKeepsThePlayersScaleEvenWhereItDoesNotFit() {
        assertEquals(4, UiScale.menuScale(4, 1920, 1080, BotConfig.LUNE_UI_MATCH_GAME));
        assertEquals(9, UiScale.menuScale(9, 3840, 2160, BotConfig.LUNE_UI_MATCH_GAME));
    }

    @Test
    void compactBuysMoreRoomWithinTheSameFloor() {
        // Compact asks for 800x430, which 1080p only reaches at 2 - still half of 4, so allowed.
        assertEquals(2, UiScale.menuScale(4, 1920, 1080, BotConfig.LUNE_UI_COMPACT));
    }

    @Test
    void isNeverLargerThanTheGameScale() {
        for (int gameScale = 1; gameScale <= 10; gameScale++) {
            assertTrue(UiScale.menuScale(gameScale, 1920, 1080, AUTO) <= gameScale);
            assertTrue(UiScale.menuScale(gameScale, 1920, 1080, AUTO) >= 1);
        }
    }

    /**
     * The text size gets the last word over the layout.
     *
     * <p>Everything before it is the panels arguing about how much room they would like. This is
     * somebody saying they cannot read it, which outranks the argument - so a positive step is
     * allowed to push the layout back below the size it asked for.</p>
     */
    @Test
    void theTextSizeStepMovesTheFittedScale() {
        int fitted = UiScale.menuScale(4, 1920, 1080, AUTO);
        assertEquals(3, fitted);
        assertEquals(fitted - 1, UiScale.menuScale(4, 1920, 1080, AUTO, -1));
        assertEquals(fitted + 1, UiScale.menuScale(4, 1920, 1080, AUTO, 1));
    }

    /** Never past the player's own scale, however large a step they asked for. */
    @Test
    void theTextSizeStepStopsAtTheGameScale() {
        assertEquals(4, UiScale.menuScale(4, 1920, 1080, AUTO, 2));
        assertEquals(4, UiScale.menuScale(4, 1920, 1080, BotConfig.LUNE_UI_MATCH_GAME, 2));
        for (int step = -3; step <= 3; step++) {
            int scale = UiScale.menuScale(4, 1920, 1080, AUTO, step);
            assertTrue(scale >= 1 && scale <= 4, "step " + step + " gave scale " + scale);
        }
    }

    /** A step of zero is the behaviour every existing caller already had. */
    @Test
    void noStepIsTheOldAnswer() {
        for (int gameScale = 1; gameScale <= 10; gameScale++) {
            assertEquals(UiScale.menuScale(gameScale, 1920, 1080, AUTO),
                    UiScale.menuScale(gameScale, 1920, 1080, AUTO, 0));
        }
    }
}
