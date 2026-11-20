package com.etka.lune.client.gui.widget;

import java.util.function.Predicate;

/**
 * Lune's tour of her own panel: the stops it makes, in order, and which one it is on.
 *
 * <p>The user asked for this (2026-10-24): the first time the panel opens, Lune offers to show a
 * new player round - the tabs, where the cards are found, where a task is made, how it runs beside
 * them and how it is shared - or to let them skip it. {@link TourOverlay} draws it and lights each
 * stop; what is here is the order and the bookkeeping, free of Minecraft so a test can walk it.</p>
 *
 * <p>A stop the panel cannot show as it stands is passed over, never lit dark: inside a training
 * lesson the task list is the lesson's brief, so the stops about the list are skipped, and the
 * progress segments on the card count only the stops the tour will make.</p>
 */
public final class PanelTour {

    /**
     * Raised when the tour changes enough to be worth offering again to everybody who has already
     * answered the offer; compared with {@code BotConfig.tourOffered}.
     */
    public static final int VERSION = 1;

    /** The tab a stop is shown over. */
    public enum Place {
        MAIN,
        TASK,
        /** Wherever the panel already is: the stop lights something every tab shares. */
        HERE
    }

    /**
     * Every stop, in the order the tour makes them. The keys are written out so the language test
     * finds them; nothing here resolves a word.
     */
    public enum Stop {
        TABS(Place.MAIN, "lune.tour.tabs.title", "lune.tour.tabs.text"),
        MAIN_NOW(Place.MAIN, "lune.tour.main_now.title", "lune.tour.main_now.text"),
        MAIN_TASKS(Place.MAIN, "lune.tour.main_tasks.title", "lune.tour.main_tasks.text"),
        TASK_LIST(Place.TASK, "lune.tour.task_list.title", "lune.tour.task_list.text"),
        TASK_CARDS(Place.TASK, "lune.tour.task_cards.title", "lune.tour.task_cards.text"),
        TASK_CANVAS(Place.TASK, "lune.tour.task_canvas.title", "lune.tour.task_canvas.text"),
        TASK_SETTINGS(Place.TASK, "lune.tour.task_settings.title", "lune.tour.task_settings.text"),
        TASK_BESIDE(Place.TASK, "lune.beside.state", "lune.tour.task_beside.text"),
        /**
         * Its own stop rather than a sentence on the one before: "the key cap gives it its own
         * key" told the user nothing (2026-10-25), and the + is a control of its own.
         */
        TASK_KEY(Place.TASK, "lune.tour.task_key.title", "lune.tour.task_key.text"),
        TASK_BUTTONS(Place.TASK, "lune.tour.task_buttons.title", "lune.tour.task_buttons.text"),
        OTHER_TABS(Place.HERE, "lune.tour.other_tabs.title", "lune.tour.other_tabs.text"),
        MENU(Place.HERE, "lune.tour.menu.title", "lune.tour.menu.text");

        private final Place place;
        private final String titleKey;
        private final String textKey;

        Stop(Place place, String titleKey, String textKey) {
            this.place = place;
            this.titleKey = titleKey;
            this.textKey = textKey;
        }

        public Place place() {
            return place;
        }

        public String titleKey() {
            return titleKey;
        }

        public String textKey() {
            return textKey;
        }

        /** Whether the stop is about the task list's pane, which a lesson or a narrow screen hides. */
        public boolean needsTaskList() {
            return this == TASK_LIST || this == TASK_BESIDE || this == TASK_KEY || this == TASK_BUTTONS;
        }
    }

    /** The stop on show, or -1 while the tour is not running. */
    private int current = -1;

    /** Whether the panel should offer the tour to somebody who last answered offer {@code answered}. */
    public static boolean shouldOffer(int answered) {
        return answered < VERSION;
    }

    public boolean running() {
        return current >= 0;
    }

    /** The stop on show, or null while the tour is not running. */
    public Stop stop() {
        return running() ? Stop.values()[current] : null;
    }

    /**
     * Starts at {@code from}, or at the first stop after it the panel can show. Returns false, and
     * runs nothing, when none of them can be shown.
     */
    public boolean start(Stop from, Predicate<Stop> shown) {
        current = find(from == null ? 0 : from.ordinal(), 1, shown);
        return running();
    }

    /** Goes on to the next stop that can be shown; after the last one the tour is over. */
    public boolean next(Predicate<Stop> shown) {
        if (!running()) {
            return false;
        }
        current = find(current + 1, 1, shown);
        return running();
    }

    /** Goes back to the stop before; on the first one it stays where it is. */
    public boolean back(Predicate<Stop> shown) {
        if (!running()) {
            return false;
        }
        int before = find(current - 1, -1, shown);
        if (before < 0) {
            return false;
        }
        current = before;
        return true;
    }

    public void end() {
        current = -1;
    }

    /** True on the first stop the tour makes, where there is nothing to go back to. */
    public boolean first(Predicate<Stop> shown) {
        return running() && find(current - 1, -1, shown) < 0;
    }

    /** True on the last stop the tour makes, where going on finishes it. */
    public boolean last(Predicate<Stop> shown) {
        return running() && find(current + 1, 1, shown) < 0;
    }

    /** How far along the tour is, counting only the stops it makes: 1 on the first. */
    public int position(Predicate<Stop> shown) {
        if (!running()) {
            return 0;
        }
        int position = 0;
        for (Stop stop : Stop.values()) {
            if (stop.ordinal() <= current && shown.test(stop)) {
                position++;
            }
        }
        return position;
    }

    /** How many stops the tour makes on the panel as it stands. */
    public static int count(Predicate<Stop> shown) {
        int count = 0;
        for (Stop stop : Stop.values()) {
            if (shown.test(stop)) {
                count++;
            }
        }
        return count;
    }

    private static int find(int from, int step, Predicate<Stop> shown) {
        Stop[] stops = Stop.values();
        for (int index = from; index >= 0 && index < stops.length; index += step) {
            if (shown.test(stops[index])) {
                return index;
            }
        }
        return -1;
    }
}
