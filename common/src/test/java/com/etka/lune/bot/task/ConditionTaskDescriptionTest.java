package com.etka.lune.bot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionTaskDescriptionTest {

    @Test
    void describesEveryComparisonAsTheBranchTheUserWillSee() {
        assertEquals("If player Health is greater than 4: Success; otherwise: Fail.",
                ConditionText.describeRule("player Health", "Greater than", 4));
        assertEquals("If player Health is less than 4: Success; otherwise: Fail.",
                ConditionText.describeRule("player Health", "Less than", 4));
        assertEquals("If player Air is equal to 3: Success; otherwise: Fail.",
                ConditionText.describeRule("player Air", "Equal to", 3));
        assertEquals("If player Air is not equal to 3: Success; otherwise: Fail.",
                ConditionText.describeRule("player Air", "Not equal", 3));
        assertEquals("If player Health is at least 4: Success; otherwise: Fail.",
                ConditionText.describeRule("player Health", "At least", 4));
        assertEquals("If player Health is at most 4: Success; otherwise: Fail.",
                ConditionText.describeRule("player Health", "At most", 4));
    }
}
