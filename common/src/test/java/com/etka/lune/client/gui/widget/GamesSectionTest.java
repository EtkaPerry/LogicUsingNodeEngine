package com.etka.lune.client.gui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GamesSectionTest {

    /** At the panel's usual 640 by 360, a Games page leaves a board box of about 330 by 238. */
    @Test
    void tilesGrowToFillTheBoxInEvenSteps() {
        assertEquals(40, WiresPage.tileSize(330, 238, 5, 5));
        assertEquals(32, WiresPage.tileSize(330, 238, 7, 7));
        assertEquals(24, WiresPage.tileSize(330, 238, 9, 9));
        assertEquals(16, WiresPage.tileSize(330, 238, 13, 11));
    }

    /**
     * A size chip's count, with every character six pixels wide as the game's digits are: the
     * number while it fits beside the size, then "99+", then the tick alone.
     */
    @Test
    void aChipCountsItsClearsAndShortensWhenCrowded() {
        java.util.function.ToIntFunction<String> width = text -> text.length() * 6;
        assertEquals("", WiresPage.solvedCount(width, 30, 54, 0), "nothing solved, nothing shown");
        assertEquals("7", WiresPage.solvedCount(width, 30, 54, 7));
        assertEquals("42", WiresPage.solvedCount(width, 30, 54, 42));
        assertEquals("", WiresPage.solvedCount(width, 30, 54, 123), "a crowded chip keeps the tick alone");
        assertEquals("345", WiresPage.solvedCount(width, 20, 54, 345), "a short size has room for more");
        assertEquals("99+", WiresPage.solvedCount(width, 20, 54, 12_345));
    }

    @Test
    void aCrampedBoardGetsEveryPixelItCanAndAHugeOneStopsGrowing() {
        assertEquals(12, WiresPage.tileSize(192, 150, 13, 11));
        assertEquals(4, WiresPage.tileSize(10, 10, 13, 11));
        assertEquals(48, WiresPage.tileSize(1000, 1000, 5, 5));
    }
}
