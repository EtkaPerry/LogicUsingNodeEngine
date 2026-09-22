package com.etka.lune.waypoint;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The names a task gets when it saves a place without naming it. What matters is that it always
 * gets one, and never the same one twice - a card in a loop must not overwrite what it saved a
 * minute ago.
 */
class WaypointNamesTest {

    @Test
    void anUnusedNameIsOneOfTheFlowers() {
        String suggested = WaypointNames.suggest(List.of(), new Random(1));
        assertTrue(WaypointNames.flowers().contains(suggested), suggested);
    }

    @Test
    void takenNamesAreNeverSuggestedAgain() {
        List<String> taken = new ArrayList<>(WaypointNames.flowers());
        String last = taken.remove(taken.size() - 1);
        assertEquals(last, WaypointNames.suggest(taken, new Random(7)),
                "with one flower left, that is the only answer");
    }

    /**
     * A name matches whatever its case, and the store's rule is equalsIgnoreCase - which, on
     * Turkish text, is not the same as comparing two lowercased strings. Getting this wrong hands
     * out a name the store then quietly overwrites.
     */
    @Test
    void matchingIgnoresCase() {
        List<String> taken = WaypointNames.flowers().stream()
                .map(name -> name.toUpperCase(Locale.ROOT))
                .toList();
        String suggested = WaypointNames.suggest(taken, new Random(3));
        assertFalse(WaypointNames.flowers().contains(suggested),
                "every flower is taken in upper case, so a bare flower name is not free");
        assertTrue(suggested.endsWith(" 2"), suggested);
    }

    /**
     * A breadcrumb card runs forever, so the list running out is a normal Tuesday rather than an
     * error: the flowers come round again as Lale 2, Lale 3, and never repeat.
     */
    @Test
    void namesKeepComingWhenTheFlowersRunOut() {
        Set<String> saved = new HashSet<>();
        Random random = new Random(11);
        for (int i = 0; i < WaypointNames.flowers().size() * 3; i++) {
            String next = WaypointNames.suggest(saved, random);
            assertTrue(saved.add(next), "suggested '" + next + "' twice");
        }
        assertEquals(WaypointNames.flowers().size() * 3, saved.size());
        assertTrue(saved.stream().anyMatch(name -> name.endsWith(" 2")));
        assertTrue(saved.stream().anyMatch(name -> name.endsWith(" 3")));
    }

    @Test
    void theListLeadsWithTheOnesItWasGiven() {
        assertEquals(List.of("Lale", "Yasemin", "Papatya", "Karanfil", "Kardelen", "Gelincik",
                        "Orkide", "Zambak", "Nergis", "Leylak", "Lavanta"),
                WaypointNames.flowers().subList(0, 11));
        assertTrue(WaypointNames.flowers().size() > 11, "and then some");
        assertEquals(WaypointNames.flowers().size(),
                new HashSet<>(WaypointNames.flowers()).size(), "no duplicates");
    }

    /** Two runs of an unnamed card should not keep landing on the same flower. */
    @Test
    void theChoiceIsNotAlwaysTheSame() {
        Set<String> seen = new HashSet<>();
        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            seen.add(WaypointNames.suggest(List.of(), random));
        }
        assertNotEquals(1, seen.size(), "an empty list should not always give the same name");
    }
}
