package com.etka.lune.client.gui.tab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void splitsTheRightSideAndKeepsFourBottomColumnsWhenThereIsRoom() {
        DashboardFrame frame = frame(640, 360);
        assertNotNull(frame.recentActivity());
        assertNotNull(frame.workingNode());
        assertEquals(frame.status().y(), frame.recentActivity().y(), "top cards share a row");
        assertTrue(frame.recentActivity().x() > frame.status().x());
        assertEquals(frame.recentActivity().y(), frame.workingNode().y(),
                "top cards share a row");
        assertTrue(frame.workingNode().x() > frame.recentActivity().x());
        assertTrue(frame.tasks().x() < frame.safetyVitals().x());
        assertTrue(frame.safetyVitals().x() < frame.safetyWorld().x());
        assertTrue(frame.safetyWorld().x() < frame.statistics().x());
        assertEquals(frame.tasks().y(), frame.safetyVitals().y());
        assertEquals(frame.safetyVitals().y(), frame.safetyWorld().y());
        assertEquals(frame.safetyWorld().y(), frame.statistics().y());
        assertEquals(DashboardFrame.CARD_H, frame.status().height());
        assertTrue(frame.status().rows() >= 6, "the top card keeps the expanded monitor details");
    }

    @Test
    void letsLineSpacingChangeHowManyDetailsFit() {
        DashboardFrame compact = DashboardFrame.of(0, 24, 640, 360, DashboardFrame.MIN_ROW_H);
        DashboardFrame spacious = DashboardFrame.of(0, 24, 640, 360, DashboardFrame.MAX_ROW_H);
        assertEquals(DashboardFrame.MIN_ROW_H, compact.status().lineHeight());
        assertEquals(DashboardFrame.MAX_ROW_H, spacious.status().lineHeight());
        assertTrue(compact.status().rows() >= spacious.status().rows(),
                "tighter lines should fit at least as many details");
    }

    @Test
    void movesStatusAboveTheTwoRightCardsWhenNarrow() {
        DashboardFrame frame = frame(380, 300);
        assertNotNull(frame.recentActivity());
        assertNotNull(frame.workingNode());
        assertEquals(frame.status().x(), frame.recentActivity().x(), "status starts the second row");
        assertTrue(frame.recentActivity().y() > frame.status().y() + frame.status().height() - 1);
        assertTrue(frame.workingNode().x() > frame.recentActivity().x());
        assertTrue(frame.status().width() >= DashboardFrame.MIN_CARD_W,
                "the narrow layout keeps the status readable");
    }

    @Test
    void keepsTheCompactRightCardsVisibleWhenHeightIsTight() {
        DashboardFrame frame = frame(380, 190);
        assertNotNull(frame.recentActivity());
        assertNotNull(frame.workingNode());
        assertTrue(frame.status().rows() >= 1);
    }

    @Test
    void givesUpCardLinesOneAtATimeAsHeightRunsout() {
        int roomy = frame(640, 360).status().rows();
        int tight = frame(380, 300).status().rows();
        int tightest = frame(380, 190).status().rows();
        assertTrue(roomy >= 6, "a normal top card should show the expanded monitor details");
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
                assertTrue(frame.recentActivity().width() > 0,
                        "activity card inverted at " + width + "x" + height);
                assertTrue(frame.workingNode().width() > 0,
                        "working node card inverted at " + width + "x" + height);
                assertTrue(frame.tasks().width() > 0 && frame.safetyVitals().width() > 0
                                && frame.safetyWorld().width() > 0
                                && frame.statistics().width() > 0,
                        "bottom columns inverted at " + width + "x" + height);
            }
        }
    }

    @Test
    void acceptsDraggableWidthsWithoutInvertingAnyColumn() {
        DashboardFrame frame = DashboardFrame.of(0, 24, 960, 540, DashboardFrame.ROW_H,
                180, 300, 220, 220, 260);
        assertEquals(180, frame.status().width());
        assertEquals(300, frame.recentActivity().width());
        assertEquals(220, frame.tasks().width());
        assertEquals(220, frame.safetyVitals().width());
        assertEquals(260, frame.safetyWorld().width());
        assertTrue(frame.workingNode().width() > 0);
        assertTrue(frame.statistics().width() > 0);
    }

    @Test
    void sharesTheButtonRowOnlyWhenTheNaturalWidthsDoNotFit() {
        assertFalse(frame(640, 360).sharedButtonRow());
        assertTrue(frame(240, 360).sharedButtonRow(), "three buttons cannot keep their own widths");
    }
}
