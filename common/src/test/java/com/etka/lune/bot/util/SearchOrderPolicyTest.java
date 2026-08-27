package com.etka.lune.bot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchOrderPolicyTest {

    @Test
    void worksLevelWithTheSiteFirst() {
        assertEquals(0, SearchOrderPolicy.verticalPreference(70, 70));
    }

    @Test
    void takesTheBlockUnderfootLastOfAll() {
        // Inside a solid deposit every neighbour is one block away, so this ordering is the only
        // thing between mining across the face and sinking a shaft through it.
        int level = SearchOrderPolicy.verticalPreference(70, 70);
        int above = SearchOrderPolicy.verticalPreference(70, 71);
        int below = SearchOrderPolicy.verticalPreference(70, 69);
        assertTrue(level < above, "level with the work comes before climbing");
        assertTrue(above < below, "anything at all comes before digging underfoot");
    }

    @Test
    void onlyTheSideMattersNotHowFar() {
        // Distance has already been compared by the time this runs, so a candidate ten below must
        // not be ranked differently from one just below.
        assertEquals(SearchOrderPolicy.verticalPreference(70, 69),
                SearchOrderPolicy.verticalPreference(70, 60));
        assertEquals(SearchOrderPolicy.verticalPreference(70, 71),
                SearchOrderPolicy.verticalPreference(70, 80));
    }
}
