package com.etka.lune.client.gui.tab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard has to survive every screen the GUI scale can hand it, including the ones Lune's
 * own scale rule cannot rescue. These fix what it gives up, and in which order.
 */
class DashboardFrameTest {

    /** Content area below the 24px tab bar, for a screen of the given size. */
    private static DashboardFrame frame(int width, int height) {
        return DashboardFrame.of(0, 24, width, height);
    }

    @Test
    void keepsBothCardsSideBySideWhenThereIsRoom() {
        DashboardFrame frame = frame(640, 360);
        assertNotNull(frame.telemetry());
        assertEquals(frame.status().y(), frame.telemetry().y(), "cards share a row");
        assertTrue(frame.telemetry().x() > frame.status().x());
        assertEquals(DashboardFrame.CARD_H, frame.status().height());
        assertEquals(DashboardFrame.MAX_ROWS, frame.status().rows(), "every line is shown");
    }

    @Test
    void stacksTheCardsRatherThanSqueezingThemNarrow() {
        DashboardFrame frame = frame(380, 300);
        assertNotNull(frame.telemetry());
        assertEquals(frame.status().x(), frame.telemetry().x(), "one column");
        assertTrue(frame.telemetry().y() > frame.status().y() + frame.status().height() - 1);
        assertTrue(frame.status().width() >= DashboardFrame.MIN_CARD_W,
                "stacking is what buys the width back");
    }

    @Test
    void dropsTelemetryWhenTwoStackedCardsWouldNotFit() {
        DashboardFrame frame = frame(380, 190);
        assertNull(frame.telemetry(), "bot status is the one thing this tab must answer");
        assertTrue(frame.status().rows() >= 1);
    }

    @Test
    void givesUpCardLinesOneAtATimeAsHeightRunsout() {
        int roomy = frame(640, 360).status().rows();
        int tight = frame(380, 300).status().rows();
        int tightest = frame(380, 190).status().rows();
        assertEquals(DashboardFrame.MAX_ROWS, roomy);
        assertTrue(tight < roomy && tight >= 1, "some lines survive: " + tight);
        assertTrue(tightest <= tight, "shorter never shows more");
    }

    @Test
    void neverLetsAPanelRunPastTheBottomEdge() {
        for (int width = 200; width <= 960; width += 20) {
            for (int height = 160; height <= 540; height += 20) {
                DashboardFrame frame = frame(width, height);
                assertTrue(frame.hintY() + 9 <= height,
                        "hint clipped at " + width + "x" + height);
                assertTrue(frame.buttonY() + DashboardFrame.CONTROL_H <= frame.hintY() + 9,
                        "buttons overlap the hint at " + width + "x" + height);
                assertTrue(frame.status().width() > 0, "card inverted at " + width + "x" + height);
                if (frame.telemetry() != null) {
                    assertTrue(frame.telemetry().width() > 0,
                            "telemetry card inverted at " + width + "x" + height);
                    assertTrue(frame.telemetry().width() >= DashboardFrame.MIN_CARD_W,
                            "telemetry card starved at " + width + "x" + height);
                }
            }
        }
    }

    @Test
    void sharesTheButtonRowOnlyWhenTheNaturalWidthsDoNotFit() {
        assertFalse(frame(640, 360).sharedButtonRow());
        assertTrue(frame(240, 360).sharedButtonRow(), "three buttons cannot keep their own widths");
    }
}
