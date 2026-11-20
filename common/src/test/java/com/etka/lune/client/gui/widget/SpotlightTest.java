package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.widget.Spotlight.Box;
import com.etka.lune.client.gui.widget.Spotlight.Placement;
import com.etka.lune.client.gui.widget.Spotlight.Side;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tour's dim and light, and where its card stands, on a panel laid out the size of the real one. */
class SpotlightTest {

    /** The smallest panel the tabs are laid out for, in Lune's pixels. */
    private static final Box SCREEN = new Box(0, 0, 620, 330);
    private static final int GAP = 8;
    private static final int MARGIN = 4;
    private static final int WIDE = 228;
    private static final int NARROW = 168;
    /** A card that grows a line for every forty pixels it is narrowed, as wrapped words do. */
    private static final IntUnaryOperator HEIGHT = width -> 60 + (WIDE - width) / 40 * 10;

    private static Placement place(List<Box> holes) {
        return Spotlight.place(SCREEN, holes, GAP, MARGIN, WIDE, NARROW, HEIGHT);
    }

    private static void assertClear(Placement placement, List<Box> holes) {
        for (Box hole : holes) {
            assertEquals(0, placement.card().overlap(hole), placement + " covers " + hole);
        }
    }

    private static void assertOnScreen(Box card) {
        assertTrue(card.x() >= MARGIN && card.y() >= MARGIN
                && card.right() <= SCREEN.right() - MARGIN
                && card.bottom() <= SCREEN.bottom() - MARGIN, card + " leaves the screen");
    }

    /** Every pixel is under exactly one dim box, or under a hole and no dim box at all. */
    private static void assertCoveredOnce(Box screen, List<Box> holes) {
        List<Box> dim = Spotlight.shade(screen, holes);
        for (int y = screen.y(); y < screen.bottom(); y++) {
            for (int x = screen.x(); x < screen.right(); x++) {
                int under = 0;
                for (Box box : dim) {
                    if (box.contains(x + 0.5, y + 0.5)) {
                        under++;
                    }
                }
                boolean lit = false;
                for (Box hole : holes) {
                    lit |= hole.contains(x + 0.5, y + 0.5);
                }
                assertEquals(lit ? 0 : 1, under, "pixel " + x + "," + y + " lit=" + lit);
            }
        }
    }

    @Test
    void theDimAndTheLightCoverTheScreenExactlyOnce() {
        Box small = new Box(0, 0, 60, 40);
        assertCoveredOnce(small, List.of());
        assertCoveredOnce(small, List.of(new Box(10, 5, 20, 10)));
        // Overlapping, touching, and running off the edges.
        assertCoveredOnce(small, List.of(new Box(10, 5, 20, 10), new Box(25, 10, 20, 20),
                new Box(45, 10, 5, 20), new Box(-5, 30, 20, 20), new Box(50, -3, 30, 8)));
        // The whole screen lit leaves nothing to dim.
        assertTrue(Spotlight.shade(small, List.of(new Box(-1, -1, 70, 50))).isEmpty());
    }

    @Test
    void theTabBarIsExplainedFromBelow() {
        List<Box> bar = List.of(new Box(110, 0, 400, 24));
        Placement placement = place(bar);
        assertEquals(Side.BELOW, placement.side());
        assertEquals(24 + GAP, placement.card().y());
        assertEquals(bar.get(0).centreX(), placement.card().centreX());
        assertClear(placement, bar);
    }

    @Test
    void aTallPaneOnTheLeftIsExplainedFromItsRight() {
        List<Box> list = List.of(new Box(8, 32, 132, 290));
        Placement placement = place(list);
        assertEquals(Side.RIGHT, placement.side());
        assertEquals(list.get(0).right() + GAP, placement.card().x());
        assertClear(placement, list);
        assertOnScreen(placement.card());
    }

    @Test
    void aCardStaysClearOfEveryLitPartNotOnlyTheFirst() {
        // What she is doing across the top, and the buttons that stop her along the bottom: the
        // card goes between them rather than over either.
        List<Box> holes = List.of(new Box(8, 32, 604, 116), new Box(8, 280, 260, 40));
        Placement placement = place(holes);
        assertEquals(Side.BELOW, placement.side());
        assertClear(placement, holes);

        // With the second right where below would put it, the card finds another side.
        List<Box> crowded = List.of(new Box(200, 100, 100, 40), new Box(150, 140, 300, 100));
        Placement moved = place(crowded);
        assertNotEquals(Side.BELOW, moved.side());
        assertClear(moved, crowded);
    }

    @Test
    void aCardTooWideBesideTheLightNarrowsBeforeItCoversAnything() {
        // The canvas between the list and the palette: no room above or below, and only 180
        // pixels to its right - too few for the card at its own width, enough at its narrowest.
        List<Box> canvas = List.of(new Box(146, 50, 290, 274));
        Placement placement = place(canvas);
        assertTrue(placement.side() == Side.RIGHT || placement.side() == Side.LEFT, placement.toString());
        assertEquals(NARROW, placement.card().width());
        assertClear(placement, canvas);
        assertOnScreen(placement.card());
    }

    @Test
    void withNoSideClearTheCardCoversTheLeastAndPointsAtNothing() {
        List<Box> everything = List.of(new Box(20, 20, 580, 290));
        Placement placement = place(everything);
        assertEquals(Side.NONE, placement.side());
        assertOnScreen(placement.card());
    }

    @Test
    void withNothingLitTheCardStandsInTheMiddle() {
        Placement placement = place(List.of());
        assertEquals(Side.NONE, placement.side());
        assertEquals(SCREEN.centreX(), placement.card().centreX(), 1);
        assertEquals(SCREEN.centreY(), placement.card().centreY(), 1);
    }

    @Test
    void aCardBelowSomethingAtTheEdgeSlidesBackOnScreen() {
        // The menu button, in the corner of the tab bar.
        List<Box> menu = List.of(new Box(560, 3, 54, 18));
        Placement placement = place(menu);
        assertEquals(Side.BELOW, placement.side());
        assertOnScreen(placement.card());
        assertEquals(SCREEN.right() - MARGIN, placement.card().right());
    }
}
