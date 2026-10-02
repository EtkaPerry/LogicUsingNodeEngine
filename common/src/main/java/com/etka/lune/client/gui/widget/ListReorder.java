package com.etka.lune.client.gui.widget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A row of a {@link ListPanel} lifted by holding it, and the place it is about to be moved to.
 *
 * <p>Held rather than simply dragged, because a press on a task row is already the click that opens
 * the task: a drag that began on the press would move tasks every time a click wobbled. Kept still
 * for {@link #HOLD_MILLIS}, the row lifts and follows the pointer from place to place, the rows in
 * between moving along to make room; let go and it stays in the place it was shown in. Back where
 * it began, or beside the list, and nothing has moved. Kept out of the widget, like
 * {@link ListSelection}, so the rules can be tested without a game.</p>
 *
 * <p>A move and not a swap. The first version changed the lifted row round with the row it was
 * dropped on, and the user asked for it to go between the rows instead (2026-10-01): putting
 * a task where it belongs is one gesture, where swapping would take one per row it passes.</p>
 *
 * <h2>Frames decide, not mouse events</h2>
 *
 * <p>A mouse kept still sends nothing, so it is the frames that see a hold through: every frame
 * reports where the pointer is ({@link #frame}). They also decide where a drop lands. The pointer
 * reaches a frame and a mouse event rounded differently - the frame's reading is up to a couple of
 * the panel's pixels short of the event's - so the two can name neighbouring rows near a row's
 * edge. Judging the hold by the event's row ended every hold pressed near the top of a row on its
 * first frame, and a drop judged by the event's row could land a place away from where the frames
 * had shown it. So the frames are asked both, and what is on screen when the button comes up is
 * what happens.</p>
 *
 * <p>For the same reason a lifted row stays where it began until the pointer leaves the spot the
 * hold began on. A press near the top of a row reads as the row above on every frame, and without
 * that the row would come up already moved a place, with the mouse never having moved.</p>
 */
final class ListReorder<T> {

    /** How long a row is held before it lifts: long enough that no click ever gets there. */
    static final long HOLD_MILLIS = 1000L;
    /**
     * How long a press lasts before its row starts showing the hold fill up. A click is over well
     * before this, so choosing a row does not flash a bar across it every time.
     */
    static final long SHOW_AFTER_MILLIS = 150L;
    /**
     * How far, in the panel's pixels, the pointer may wander from where the hold began and still be
     * on that spot: kept still before the row lifts, put back afterwards. Well under a row, so any
     * move to another row leaves it; over the pixel or two a resting hand drifts.
     */
    static final int STILL_SLOP = 4;

    /** A drop: the lifted row, and the place in the list it is to take. */
    record Move<T>(T lifted, int to) {}

    /** The row pressed, while the button is still down; null when nothing is held. */
    private T held;
    private long pressedAt;
    private boolean lifted;
    /** Whether a frame has seen this hold yet, and where that frame found the pointer. */
    private boolean anchored;
    private int anchorX;
    private int anchorY;
    /** The place the lifted row was last drawn in; -1 while it is shown where it began. */
    private int shownAt = -1;

    /** A plain press on a row starts holding it. */
    void press(T item, long now) {
        cancel();
        held = item;
        pressedAt = now;
    }

    /**
     * A frame was drawn with the pointer over place {@code slot} of the list: the row there, the
     * first or last one on show while the pointer is above or below them, or -1 beside the list.
     *
     * <p>({@code x}, {@code y}) is where the pointer is in the list's content rather than on the
     * screen, so scrolling moves it as far as moving the mouse would: rows that slid under a still
     * pointer are rows it is now over.</p>
     *
     * <p>Until the row lifts, the pointer has to stay within {@link #STILL_SLOP} of where the first
     * frame found it. Measured from that frame rather than from the press, so both ends of the
     * measurement are rounded the same way. A pointer that wandered off was a press that became
     * something else, and lifting the row a moment later would move a task nobody meant to
     * move. Once lifted, the row goes to the place under the pointer only away from that spot; back
     * on it, the row is where it began.</p>
     */
    void frame(int x, int y, int slot, long now) {
        if (held == null) {
            return;
        }
        if (!anchored) {
            anchored = true;
            anchorX = x;
            anchorY = y;
        }
        boolean home = Math.abs(x - anchorX) <= STILL_SLOP && Math.abs(y - anchorY) <= STILL_SLOP;
        if (!lifted) {
            if (!home) {
                cancel();
                return;
            }
            lifted = now - pressedAt >= HOLD_MILLIS;
        }
        if (lifted) {
            shownAt = home ? -1 : slot;
        }
    }

    /** The row held down and not lifted yet, or null. */
    T holding() {
        return lifted ? null : held;
    }

    /** How far the hold has filled, from 0 to 1; nothing at all until {@link #SHOW_AFTER_MILLIS}. */
    float progress(long now) {
        long elapsed = now - pressedAt;
        if (held == null || lifted || elapsed < SHOW_AFTER_MILLIS) {
            return 0.0F;
        }
        return Math.min(1.0F, elapsed / (float) HOLD_MILLIS);
    }

    /** The row lifted and carried, or null. */
    T lifted() {
        return lifted ? held : null;
    }

    /**
     * The place in {@code items} the lifted row is shown in as of the last frame, or -1 while
     * that is where it began - or nothing is lifted.
     */
    int target(List<T> items) {
        int from = lifted ? items.indexOf(held) : -1;
        return from < 0 || shownAt < 0 || shownAt == from || shownAt >= items.size() ? -1 : shownAt;
    }

    /**
     * The rows as the last frame left them: the lifted row taken out and put in at the place it is
     * shown in, the rows between moved along one. The list itself while nothing has moved.
     */
    List<T> preview(List<T> items) {
        int to = target(items);
        if (to < 0) {
            return items;
        }
        List<T> shown = new ArrayList<>(items);
        shown.add(to, shown.remove(items.indexOf(held)));
        return shown;
    }

    /**
     * The button came up, which ends the gesture whatever it was.
     *
     * @return the move the last frame showed, or null when nothing should move
     */
    Move<T> release(List<T> items) {
        int to = target(items);
        T moved = lifted();
        cancel();
        return to < 0 ? null : new Move<>(moved, to);
    }

    void cancel() {
        held = null;
        lifted = false;
        anchored = false;
        shownAt = -1;
    }

    /** Lets go of a held row the list no longer has, and of a place it no longer reaches. */
    void retain(Collection<T> items) {
        if (held != null && !items.contains(held)) {
            cancel();
        } else if (shownAt >= items.size()) {
            shownAt = -1;
        }
    }
}
