package com.etka.lune.client.gui.widget;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which rows of a {@link ListPanel} are selected, and which one of them is open.
 *
 * <p>The open row is the one the list reports as selected and the rest of the screen shows - on
 * the Tasks tab, the task on the canvas. The others are chosen beside it so that one action can
 * take them all, which is what deleting twenty tasks one at a time was missing. Kept out of the
 * widget so the rules can be tested without a game.</p>
 */
final class ListSelection<T> {

    private T open;
    /** Chosen beside the open row, oldest first. Never holds the open row itself. */
    private final Set<T> others = new LinkedHashSet<>();

    T open() {
        return open;
    }

    boolean contains(T item) {
        return item != null && (item.equals(open) || others.contains(item));
    }

    /** Every selected row, in the order the list shows them. */
    List<T> inOrder(List<T> items) {
        List<T> chosen = new ArrayList<>();
        for (T item : items) {
            if (contains(item)) {
                chosen.add(item);
            }
        }
        return chosen;
    }

    /** A plain click: this row on its own, and open. Null clears the selection. */
    void only(T item) {
        open = item;
        others.clear();
    }

    /**
     * Ctrl+click: adds a row and opens it, or takes it back out.
     *
     * <p>Taking out the open row opens the one chosen most recently before it, so whatever is on
     * screen is always one of the rows the list shows as selected.</p>
     *
     * @return whether the open row changed
     */
    boolean toggle(T item) {
        if (item == null) {
            return false;
        }
        if (item.equals(open)) {
            T previous = null;
            for (T other : others) {
                previous = other;
            }
            others.remove(previous);
            open = previous;
            return true;
        }
        if (others.remove(item)) {
            return false;
        }
        if (open != null) {
            others.add(open);
        }
        open = item;
        return true;
    }

    /**
     * Shift+click: every row from the open one to this one. The open row stays open, so the run
     * can be widened or narrowed again from the same end.
     *
     * @return whether the open row changed, which only happens when nothing was open
     */
    boolean range(List<T> items, T to) {
        int end = items.indexOf(to);
        if (end < 0) {
            return false;
        }
        int start = open == null ? -1 : items.indexOf(open);
        if (start < 0) {
            only(to);
            return true;
        }
        others.clear();
        for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
            T item = items.get(i);
            if (!item.equals(open)) {
                others.add(item);
            }
        }
        return false;
    }

    /** Ctrl+A: every row, leaving the open one open. */
    void all(List<T> items) {
        others.clear();
        for (T item : items) {
            if (!item.equals(open)) {
                others.add(item);
            }
        }
    }

    /** Forgets rows the list no longer has. */
    void retain(Collection<T> items) {
        if (open != null && !items.contains(open)) {
            open = null;
        }
        others.retainAll(items);
    }
}
