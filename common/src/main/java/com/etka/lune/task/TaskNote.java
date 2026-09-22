package com.etka.lune.task;

import java.util.UUID;

/**
 * A sticky note pinned to the canvas.
 *
 * <p>Deliberately not a card. A card is behaviour - it is built, started and ticked, and the rule
 * that every behaviour belongs to exactly one card only means anything while the set of cards is
 * the set of things that run. A note runs nothing; it is the same kind of editor-only mark as a
 * {@link TaskCableAnchor}, and it lives beside those rather than in the palette so nobody can drop
 * one into a circuit and wonder why it never fires.</p>
 *
 * <p>Plain mutable fields with a no-arg constructor, because these are serialised straight to JSON
 * with the task and travel with it through share/import.</p>
 */
public final class TaskNote {

    public static final int MIN_WIDTH = 72;
    public static final int MIN_HEIGHT = 44;
    public static final int DEFAULT_WIDTH = 152;
    public static final int DEFAULT_HEIGHT = 88;
    /** Long enough for a paragraph of reasoning, short enough that one note is not a document. */
    public static final int MAX_TEXT = 512;

    /** Stable within a task, so a note can be selected, moved and deleted by reference. */
    public String id = UUID.randomUUID().toString().substring(0, 8);

    public int x;
    public int y;
    public int width = DEFAULT_WIDTH;
    public int height = DEFAULT_HEIGHT;

    public String text = "";

    /**
     * Which of the editor's note colours this one wears.
     *
     * <p>An index rather than a pixel value, so a note keeps its meaning if the palette is ever
     * retuned - and so a hand-edited file cannot write an unreadable colour onto the canvas. The
     * editor wraps it into range, which is why nothing here clamps it.</p>
     */
    public int colour;

    public TaskNote() {}

    public TaskNote(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public TaskNote copy() {
        TaskNote copy = new TaskNote(x, y);
        copy.width = width;
        copy.height = height;
        copy.text = text;
        copy.colour = colour;
        return copy;
    }

    public int right() {
        return x + Math.max(MIN_WIDTH, width);
    }

    public int bottom() {
        return y + Math.max(MIN_HEIGHT, height);
    }

    public boolean contains(int pointX, int pointY) {
        return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
    }

    /** The bottom-right corner that resizes the note, sized for a pointer rather than for a pixel. */
    public boolean inResizeCorner(int pointX, int pointY) {
        return pointX >= right() - 8 && pointX < right() && pointY >= bottom() - 8
                && pointY < bottom();
    }

    /**
     * Makes a note safe to draw: big enough to see, and holding text rather than nothing.
     *
     * <p>Called from the canvas every frame as well as on load, because the two ways a note goes
     * out of range are a drag that tried to collapse it and a shared file somebody hand-edited,
     * and only one of those passes through {@code TaskStore}.</p>
     */
    public void clampSize() {
        width = Math.max(MIN_WIDTH, width);
        height = Math.max(MIN_HEIGHT, height);
        if (text == null) {
            text = "";
        } else if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
    }
}
