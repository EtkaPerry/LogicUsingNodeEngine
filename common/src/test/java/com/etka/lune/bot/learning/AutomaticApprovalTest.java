package com.etka.lune.bot.learning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticApprovalTest {

    @Test
    void firstSuccessEstablishesBaselineAndSlowerRunsAreRejected() {
        AutomaticApproval.Decision first = AutomaticApproval.evaluate(true, 100, 0);
        assertTrue(first.approved());

        AutomaticApproval.Decision equal = AutomaticApproval.evaluate(true, 100, 100);
        assertTrue(equal.approved());

        AutomaticApproval.Decision slower = AutomaticApproval.evaluate(true, 101, 100);
        assertFalse(slower.approved());
    }

    @Test
    void failureIsRejectedEvenWhenItWasFast() {
        assertFalse(AutomaticApproval.evaluate(false, 1, 100).approved());
    }
}
