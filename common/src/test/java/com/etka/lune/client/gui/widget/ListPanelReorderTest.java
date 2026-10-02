package com.etka.lune.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holding a task and carrying it to another place, through the list the two task lists are built
 * on.
 *
 * <p>Played the way the game plays it. The press is handed to {@code onClick} directly - a
 * widget's own {@code mouseClicked} plays the click sound first, and a unit test has no game to
 * play it in. While the mouse is kept still the game sends nothing at all, only frames, so a hold
 * is waited out in frames ({@code followHold}); drags and releases go through the widget as the
 * screen sends them. Lifting a row with a drag that went nowhere, which these tests once did,
 * passed here while every real hold was let go on its first frame.</p>
 *
 * <p>Each move is written down as the row that moved and the order it left behind: {@code a:bcad}
 * is a carried down two places, between c and d.</p>
 */
class ListPanelReorderTest {

    private static final int WIDTH = 120;
    private static final int ROW_X = 20;

    private long now;
    private final List<String> moves = new ArrayList<>();
    private final List<String> started = new ArrayList<>();

    private ListPanel<String> list() {
        return list(14, List.of("a", "b", "c", "d"));
    }

    /** A list tall enough for {@code rowsOnShow} rows, holding {@code rows}. */
    private ListPanel<String> list(int rowsOnShow, List<String> rows) {
        ListPanel<String> list = new ListPanel<>(0, 0, WIDTH, rowsOnShow * ListPanel.ROW_HEIGHT + 6,
                row -> row, row -> {});
        list.clock = () -> now;
        list.setItems(rows);
        list.setOnMove((moved, order) -> moves.add(moved + ":" + String.join("", order)));
        return list;
    }

    /** The middle of a row on show: three pixels of padding, then a row every {@code ROW_HEIGHT}. */
    private static double rowY(int row) {
        return 3 + ListPanel.ROW_HEIGHT * row + ListPanel.ROW_HEIGHT / 2.0;
    }

    private static MouseButtonEvent left(double x, double y, int modifiers) {
        return new MouseButtonEvent(x, y,
                new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, modifiers));
    }

    private static MouseButtonEvent onRow(int row) {
        return left(ROW_X, rowY(row), 0);
    }

    /** A frame drawn with the pointer over a row, which is all the game sends while it is still. */
    private void frameOn(ListPanel<String> list, int row) {
        list.followHold(ROW_X, (int) rowY(row), now);
    }

    /** Pressed on a row and kept still on it, frame after frame, until it lifts. */
    private void hold(ListPanel<String> list, int row) {
        list.onClick(onRow(row), false);
        now += ListReorder.HOLD_MILLIS / 2;
        frameOn(list, row);
        now += ListReorder.HOLD_MILLIS / 2;
        frameOn(list, row);
    }

    /** Carried to a row, shown there by a frame, and let go. */
    private void drop(ListPanel<String> list, int row) {
        now += 100;
        list.mouseDragged(onRow(row), 0, 0);
        frameOn(list, row);
        list.mouseReleased(onRow(row));
    }

    @Test
    void aTaskHeldForASecondAndCarriedDownGoesBetweenTheRows() {
        ListPanel<String> list = list();

        hold(list, 0);
        drop(list, 2);

        assertEquals(List.of("a:bcad"), moves);
        assertEquals("a", list.getSelected(), "the task in hand is still the one open");
    }

    @Test
    void aTaskCarriedUpGoesBetweenTheRowsToo() {
        ListPanel<String> list = list();

        hold(list, 3);
        drop(list, 1);

        assertEquals(List.of("d:adbc"), moves);
    }

    @Test
    void framesAloneLiftARowKeptStill() {
        // Sixty frames and not one mouse event, then the first drag already carries the row. Had
        // the lift waited for an event, that drag would have been a press wandering off its row.
        ListPanel<String> list = list();
        list.onClick(onRow(0), false);
        for (int frame = 0; frame < 60; frame++) {
            now += ListReorder.HOLD_MILLIS / 60 + 1;
            frameOn(list, 0);
        }

        drop(list, 3);

        assertEquals(List.of("a:bcda"), moves);
    }

    @Test
    void theListShowsTheNewOrderWithoutWaitingForItsOwner() {
        ListPanel<String> list = list();

        hold(list, 0);
        drop(list, 2);
        // Nobody has handed the list its rows again, and still the top row is b now.
        hold(list, 0);
        drop(list, 1);

        assertEquals(List.of("a:bcad", "b:cbad"), moves);
    }

    @Test
    void aClickAndAQuickDragMoveNothing() {
        ListPanel<String> list = list();

        list.onClick(onRow(0), false);
        list.mouseReleased(onRow(0));
        now += ListReorder.HOLD_MILLIS * 2;
        frameOn(list, 0);

        list.onClick(onRow(1), false);
        now += 16;
        frameOn(list, 1);
        now += 200;
        list.mouseDragged(onRow(3), 0, 0);
        frameOn(list, 3);
        now += ListReorder.HOLD_MILLIS;
        frameOn(list, 3);
        list.mouseReleased(onRow(3));

        assertTrue(moves.isEmpty(), "only a row held still lifts: " + moves);
    }

    @Test
    void aPressAtTheVeryTopOfARowLiftsThatRow() {
        // The press lands on b's top pixel, and every frame reads the pointer a pixel higher, over
        // a: the screen rounds the pointer one way for its frames and another for its events.
        ListPanel<String> list = list();
        double topOfB = 3 + ListPanel.ROW_HEIGHT + 0.5;
        int frameReading = (int) topOfB - 1;

        list.onClick(left(ROW_X, topOfB, 0), false);
        for (int frame = 0; frame < 2; frame++) {
            now += ListReorder.HOLD_MILLIS / 2;
            list.followHold(ROW_X, frameReading, now);
        }
        list.mouseReleased(left(ROW_X, topOfB, 0));
        assertTrue(moves.isEmpty(), "held and let go without moving, nothing moves: " + moves);

        list.onClick(left(ROW_X, topOfB, 0), false);
        for (int frame = 0; frame < 2; frame++) {
            now += ListReorder.HOLD_MILLIS / 2;
            list.followHold(ROW_X, frameReading, now);
        }
        drop(list, 3);
        assertEquals(List.of("b:acdb"), moves, "the row lifted is the one pressed, not the one above");
    }

    @Test
    void letGoBesideTheListNothingMoves() {
        ListPanel<String> list = list();

        hold(list, 1);
        list.followHold(WIDTH + 30, (int) rowY(3), now);
        list.mouseReleased(left(WIDTH + 30, rowY(3), 0));

        assertTrue(moves.isEmpty(), moves.toString());
    }

    @Test
    void letGoBelowTheLastRowItGoesToTheEnd() {
        ListPanel<String> list = list();

        hold(list, 1);
        drop(list, 6);

        assertEquals(List.of("b:acdb"), moves);
    }

    @Test
    void losingTheFocusLetsGoOfTheRowInHand() {
        // Tab moved the focus mid-hold: the release goes to whatever has it now. Even if this one
        // still reached the list, the row was let go when the focus left.
        ListPanel<String> list = list();
        list.setFocused(true);

        hold(list, 0);
        list.setFocused(false);
        drop(list, 2);

        assertTrue(moves.isEmpty(), moves.toString());
    }

    @Test
    void carriedPastTheRowsOnShowTheListScrollsAndTheRowRidesTheEdge() {
        ListPanel<String> list = list(3, List.of("a", "b", "c", "d", "e", "f"));
        int belowTheRows = 3 + 3 * ListPanel.ROW_HEIGHT + 1;
        int aboveTheRows = 1;

        // Below the last row on show, a row a tenth of a second until the end: a goes last.
        hold(list, 0);
        for (int frame = 0; frame < 4; frame++) {
            list.followHold(ROW_X, belowTheRows, now);
            now += 100;
        }
        list.mouseReleased(left(ROW_X, belowTheRows, 0));

        // The list now shows e, f and a. Held above the first of them, e rides back up to the top.
        hold(list, 0);
        for (int frame = 0; frame < 4; frame++) {
            list.followHold(ROW_X, aboveTheRows, now);
            now += 100;
        }
        list.mouseReleased(left(ROW_X, aboveTheRows, 0));

        assertEquals(List.of("a:bcdefa", "e:ebcdfa"), moves);
    }

    @Test
    void aShiftClickStillChoosesRowsRatherThanLiftingOne() {
        ListPanel<String> list = list();
        list.setMultiSelect(true);
        list.onClick(onRow(0), false);
        list.mouseReleased(onRow(0));

        list.onClick(left(ROW_X, rowY(1), InputConstants.MOD_SHIFT), false);
        now += ListReorder.HOLD_MILLIS;
        frameOn(list, 1);
        drop(list, 3);

        assertTrue(moves.isEmpty(), moves.toString());
        assertEquals(List.of("a", "b"), list.getSelection());
    }

    @Test
    void aPressOnARowsButtonRunsItAndLiftsNothing() {
        // The dashboard's rows end in Start, Rename and View; Start is the one tried here.
        ListPanel<String> list = list();
        list.setActions(List.of(new ListPanel.RowAction<>(GuiIcons.Icon.PLAY, "Start", started::add)));
        MouseButtonEvent onStart = left(WIDTH - 10, rowY(1), 0);

        list.onClick(onStart, false);
        now += ListReorder.HOLD_MILLIS;
        list.followHold(WIDTH - 10, (int) rowY(1), now);
        drop(list, 3);

        assertEquals(List.of("b"), started);
        assertTrue(moves.isEmpty(), moves.toString());
    }
}
