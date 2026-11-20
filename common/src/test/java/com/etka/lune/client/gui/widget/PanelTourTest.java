package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.widget.PanelTour.Place;
import com.etka.lune.client.gui.widget.PanelTour.Stop;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tour's order and bookkeeping: where it starts, what it passes over, and how it ends. */
class PanelTourTest {

    private static final Predicate<Stop> EVERY = stop -> true;
    /** The panel inside a training lesson: the lesson's brief has the task list's pane. */
    private static final Predicate<Stop> IN_A_LESSON = stop -> !stop.needsTaskList();

    private static List<Stop> walk(Stop from, Predicate<Stop> shown) {
        PanelTour tour = new PanelTour();
        List<Stop> seen = new ArrayList<>();
        if (!tour.start(from, shown)) {
            return seen;
        }
        do {
            seen.add(tour.stop());
        } while (tour.next(shown));
        return seen;
    }

    @Test
    void theTourOpensOnTheTabsAndEndsInLunesCorner() {
        List<Stop> seen = walk(Stop.TABS, EVERY);
        assertEquals(List.of(Stop.values()), seen);
        assertEquals(Stop.TABS, seen.get(0));
        assertEquals(Stop.MENU, seen.get(seen.size() - 1));
    }

    @Test
    void goingOnFromTheLastStopEndsTheTour() {
        PanelTour tour = new PanelTour();
        tour.start(Stop.MENU, EVERY);
        assertTrue(tour.last(EVERY));
        assertFalse(tour.next(EVERY));
        assertFalse(tour.running());
        assertNull(tour.stop());
    }

    @Test
    void goingBackFromTheFirstStopStaysThere() {
        PanelTour tour = new PanelTour();
        tour.start(Stop.TABS, EVERY);
        assertTrue(tour.first(EVERY));
        assertFalse(tour.back(EVERY));
        assertTrue(tour.running());
        assertEquals(Stop.TABS, tour.stop());
    }

    @Test
    void aStopThePanelCannotShowIsPassedOverBothWays() {
        List<Stop> seen = walk(Stop.TABS, IN_A_LESSON);
        assertFalse(seen.contains(Stop.TASK_LIST));
        assertFalse(seen.contains(Stop.TASK_BESIDE));
        assertFalse(seen.contains(Stop.TASK_KEY));
        assertFalse(seen.contains(Stop.TASK_BUTTONS));
        assertEquals(Stop.values().length - 4, seen.size());

        PanelTour tour = new PanelTour();
        tour.start(Stop.TASK_CARDS, IN_A_LESSON);
        assertTrue(tour.back(IN_A_LESSON));
        assertEquals(Stop.MAIN_TASKS, tour.stop(), "back skips the list it cannot show");
    }

    @Test
    void startingAtAStopThatCannotBeShownStartsAtTheNextThatCan() {
        PanelTour tour = new PanelTour();
        assertTrue(tour.start(Stop.TASK_LIST, IN_A_LESSON));
        assertEquals(Stop.TASK_CARDS, tour.stop());
        assertTrue(tour.first(stop -> stop.ordinal() >= Stop.TASK_CARDS.ordinal()));
    }

    @Test
    void whereNothingCanBeShownNothingRuns() {
        PanelTour tour = new PanelTour();
        assertFalse(tour.start(Stop.TABS, stop -> false));
        assertFalse(tour.running());
        assertEquals(0, tour.position(stop -> false));
    }

    @Test
    void theCountAndThePositionCountOnlyTheStopsTheTourMakes() {
        assertEquals(Stop.values().length, PanelTour.count(EVERY));
        assertEquals(Stop.values().length - 4, PanelTour.count(IN_A_LESSON));

        PanelTour tour = new PanelTour();
        tour.start(Stop.TASK_CARDS, EVERY);
        assertEquals(Stop.TASK_CARDS.ordinal() + 1, tour.position(EVERY));
        // Tabs, what she is doing and the saved tasks come before; the list does not count.
        assertEquals(4, tour.position(IN_A_LESSON));
    }

    @Test
    void theOfferIsMadeUntilItsVersionHasBeenAnswered() {
        assertTrue(PanelTour.shouldOffer(0));
        assertTrue(PanelTour.shouldOffer(PanelTour.VERSION - 1));
        assertFalse(PanelTour.shouldOffer(PanelTour.VERSION));
    }

    /**
     * The stops on one tab follow each other, so the tour never bounces the player from tab to tab
     * and back: once it has left a tab it does not come back to it.
     */
    @Test
    void eachTabsStopsAreTogether() {
        Set<Place> left = new HashSet<>();
        Place previous = null;
        for (Stop stop : Stop.values()) {
            Place place = stop.place();
            if (place == Place.HERE) {
                continue;
            }
            if (place != previous) {
                assertFalse(left.contains(place), stop + " comes back to a tab the tour had left");
                if (previous != null) {
                    left.add(previous);
                }
                previous = place;
            }
        }
    }

    /**
     * A stop lights one control and says what it does: the beside switch and the key beside it were
     * one stop until "the key cap gives it its own key" told the user nothing (2026-10-25).
     */
    @Test
    void theKeyHasAStopOfItsOwnAfterTheSwitch() {
        assertEquals(Stop.TASK_BESIDE.ordinal() + 1, Stop.TASK_KEY.ordinal());
        assertTrue(Stop.TASK_KEY.needsTaskList());
        assertEquals(Place.TASK, Stop.TASK_KEY.place());
    }

    @Test
    void everyStopSaysSomethingOfItsOwn() {
        Set<String> texts = new HashSet<>();
        Set<String> titles = new HashSet<>();
        for (Stop stop : Stop.values()) {
            assertTrue(texts.add(stop.textKey()), stop + " repeats another stop's words");
            assertTrue(titles.add(stop.titleKey()), stop + " repeats another stop's name");
            assertTrue(stop.textKey().startsWith("lune.tour."), stop + " keeps its words elsewhere");
        }
    }
}
