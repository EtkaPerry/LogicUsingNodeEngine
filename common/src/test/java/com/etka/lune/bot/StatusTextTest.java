package com.etka.lune.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A status has to answer two questions that used to be one.
 *
 * <p>The Main tab asks what it says, and gets the player's language. The mascot asks what it
 * means, and gets an answer that does not depend on which language the sentence came out in - it
 * used to search that sentence for the word "lava". These are the checks that the second question
 * keeps being answerable, including through a status built out of another one.</p>
 */
class StatusTextTest {

    @Test
    void rendersFromTheLanguageFile() {
        StatusText status = new StatusText().set("lune.status.detail", "walking", "42 left");
        assertEquals("walking - 42 left", status.text());
        assertEquals("lune.status.detail", status.key());
        assertFalse(status.isBlank());
    }

    @Test
    void aNestedStatusRendersInsideItsParent() {
        StatusText child = new StatusText().set("lune.status.route.refusing_dig_beside_lava");
        StatusText parent = new StatusText().set("lune.status.detail", "bridging", child);
        assertEquals("bridging - refusing to dig beside lava", parent.text());
    }

    @Test
    void aParentInheritsTheMeaningOfWhatItWraps() {
        // The wrapper key means nothing on its own; the child is the one standing in lava.
        assertEquals(StatusSignal.NONE, StatusKeys.signalOf("lune.status.detail"));
        StatusText child = new StatusText().set("lune.status.self_preservation.escaping_lava");
        assertEquals(StatusSignal.DANGER, child.signal());

        StatusText parent = new StatusText().set("lune.status.detail", "getting there", child);
        assertEquals(StatusSignal.DANGER, parent.signal(),
                "a task wrapping one that is in lava is still in lava");
    }

    @Test
    void theStrongestMeaningWins() {
        assertEquals(StatusSignal.SUCCESS, StatusSignal.DANGER.or(StatusSignal.SUCCESS));
        assertEquals(StatusSignal.DANGER, StatusSignal.WAITING.or(StatusSignal.DANGER));
        assertEquals(StatusSignal.BLOCKED, StatusSignal.BLOCKED.or(StatusSignal.NONE));
        assertEquals(StatusSignal.BLOCKED, StatusSignal.BLOCKED.or(null));
        // The order the retired keyword search happened to test in, kept on purpose.
        assertTrue(StatusSignal.SUCCESS.priority() > StatusSignal.DANGER.priority());
        assertTrue(StatusSignal.DANGER.priority() > StatusSignal.INVENTORY_FULL.priority());
        assertTrue(StatusSignal.INVENTORY_FULL.priority()
                > StatusSignal.MISSING_MATERIALS.priority());
        assertTrue(StatusSignal.MISSING_MATERIALS.priority() > StatusSignal.BLOCKED.priority());
        assertTrue(StatusSignal.BLOCKED.priority() > StatusSignal.WAITING.priority());
        assertTrue(StatusSignal.WAITING.priority() > StatusSignal.NONE.priority());
    }

    @Test
    void adoptingAnotherStatusTakesItsMeaningToo() {
        StatusText child = new StatusText().set("lune.status.route.escaping_to_air");
        StatusText adopted = new StatusText().set(child);
        assertEquals(child.key(), adopted.key());
        assertEquals(StatusSignal.DANGER, adopted.signal());

        // A copy, not a view: the child moving on must not rewrite what the parent reported.
        child.set("lune.status.detail", "a", "b");
        assertEquals("lune.status.route.escaping_to_air", adopted.key());
    }

    @Test
    void clearingLeavesNothingToSayOrMean() {
        StatusText status = new StatusText().set("lune.status.self_preservation.escaping_lava");
        status.clear();
        assertTrue(status.isBlank());
        assertEquals("", status.text());
        assertEquals(StatusSignal.NONE, status.signal());
    }

    @Test
    void theSharedEmptyStatusRefusesToBeWrittenTo() {
        // It is the default for every task that has not spoken; one task setting it would set it
        // for all of them.
        assertSame(StatusText.EMPTY, StatusText.EMPTY.set("lune.status.detail", "x", "y"));
        assertTrue(StatusText.EMPTY.isBlank());
        assertEquals("", StatusText.EMPTY.text());
    }

    @Test
    void everySignalledKeyStillExists() {
        // The table is what makes Lune's face work; a renamed status would quietly empty it.
        for (String key : StatusKeys.all().keySet()) {
            assertTrue(com.etka.lune.util.Lang.has(key),
                    "StatusKeys declares a meaning for a key that no longer exists: " + key);
        }
    }
}
