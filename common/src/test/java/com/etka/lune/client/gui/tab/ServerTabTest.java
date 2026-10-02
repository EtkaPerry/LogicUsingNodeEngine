package com.etka.lune.client.gui.tab;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where the Server tab puts its cards and the rules' buttons. */
class ServerTabTest {

    private static boolean inside(ServerTab.Card inner, int left, int top, int right, int bottom) {
        return inner.x() >= left && inner.y() >= top && inner.x() + inner.width() <= right && inner.bottom() <= bottom;
    }

    private static boolean overlaps(ServerTab.Card a, ServerTab.Card b) {
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    /** At the panel's usual 640 by 360, under a 24-pixel tab bar: two columns, nothing overlapping. */
    @Test
    void aUsualPanelHasTwoColumns() {
        ServerTab.Frame frame = ServerTab.Frame.of(0, 24, 640, 360);
        assertEquals(frame.status().x(), frame.players().x(), "where you stand, and who has Lune, share a column");
        assertTrue(frame.rules().x() > frame.status().x() + frame.status().width(), "the rules have the other");
        assertTrue(frame.players().y() > frame.status().bottom());
        for (ServerTab.Card card : new ServerTab.Card[] {frame.status(), frame.rules(), frame.players()}) {
            assertTrue(inside(card, 0, 24, 640, 360), card.toString());
        }
        assertTrue(!overlaps(frame.status(), frame.rules()) && !overlaps(frame.players(), frame.rules())
                && !overlaps(frame.status(), frame.players()));
        assertTrue(frame.status().height() >= ServerTab.STATUS_H);
        assertTrue(frame.rules().height() >= ServerTab.RULES_H, "both rules and their footnote fit");
    }

    /** The three answers sit side by side inside the rules card, for both rules, and never touch. */
    @Test
    void theAnswersFitInsideTheRulesCard() {
        for (int width : new int[] {640, 480, 400, 300}) {
            ServerTab.Frame frame = ServerTab.Frame.of(0, 24, width, 360);
            ServerTab.Card rules = frame.rules();
            int buttonWidth = frame.buttonWidth();
            for (int i = 0; i < 3; i++) {
                int x = frame.buttonX(i);
                assertTrue(x >= rules.x() + ServerTab.PAD, "width " + width);
                assertTrue(x + buttonWidth <= rules.x() + rules.width() - ServerTab.PAD, "width " + width);
                if (i > 0) {
                    assertTrue(x >= frame.buttonX(i - 1) + buttonWidth + ServerTab.BUTTON_GAP, "width " + width);
                }
            }
            for (int rule = 0; rule < 2; rule++) {
                assertTrue(frame.buttonsY(rule) > frame.ruleY(rule), "a rule's label sits over its buttons");
                assertTrue(frame.buttonsY(rule) + ServerTab.BUTTON_H + 1 < frame.footnoteY(),
                        "the chosen answer's underline clears the footnote");
            }
            assertTrue(frame.buttonsY(0) + ServerTab.BUTTON_H < frame.ruleY(1), "the rules do not touch");
        }
    }

    /** A player's name never parts from their version: the list breaks only between players. */
    @Test
    void theListBreaksBetweenPlayersNeverInsideOne() {
        ToIntFunction<String> width = text -> text.length() * 6;
        List<String> players = List.of("Etka (Lune 0.7.3)", "Steve (Lune 0.7.3)",
                "Alex_Builds (Lune 0.7.3)");
        assertEquals(List.of("Etka (Lune 0.7.3) · Steve (Lune 0.7.3)", "Alex_Builds (Lune 0.7.3)"),
                ServerTab.rows(players, width, 240));
        assertEquals(players, ServerTab.rows(players, width, 60), "one to a line when nothing else fits");
        assertEquals(List.of(), ServerTab.rows(List.of(), width, 240));
    }

    /** Too narrow for two columns: one, top to bottom, in the order they are read. */
    @Test
    void aNarrowPanelStacksTheCards() {
        ServerTab.Frame frame = ServerTab.Frame.of(0, 24, 400, 460);
        assertEquals(frame.status().x(), frame.rules().x());
        assertTrue(frame.rules().y() > frame.status().bottom());
        assertTrue(frame.players().y() > frame.rules().bottom());
        assertTrue(inside(frame.players(), 0, 24, 400, 460));
    }
}
