package com.etka.lune.client.gui.mascot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel's bubble and the card in the corner of the world dress her words from one table, so
 * the table has to be whole: every face has a banner, and a job in hand is never given the room
 * that trouble is.
 */
class MascotSpeechTest {

    @Test
    void everyFaceHasABannerFromTheLanguageFile() {
        for (MascotAdvisor.Mood mood : MascotAdvisor.Mood.values()) {
            String banner = MascotSpeech.banner(mood);
            assertFalse(banner.isBlank(), mood + " has an empty banner");
            assertFalse(banner.startsWith("lune."), mood + " shows a key, not a line: " + banner);
        }
    }

    @Test
    void theJobFacesKeepTheBannerTheyWoreAsWork() {
        MascotAdvisor.Mood[] jobs = {MascotAdvisor.Mood.DIGGING, MascotAdvisor.Mood.FARMING,
                MascotAdvisor.Mood.CRAFTING, MascotAdvisor.Mood.SMELTING, MascotAdvisor.Mood.COLLECTING};
        for (MascotAdvisor.Mood job : jobs) {
            assertEquals(MascotSpeech.banner(MascotAdvisor.Mood.WORKING), MascotSpeech.banner(job),
                    job + " reads differently from the work it used to be shown as");
            assertEquals(MascotSpeech.colour(MascotAdvisor.Mood.WORKING), MascotSpeech.colour(job),
                    job + " is drawn in another colour from the work it used to be shown as");
        }
    }

    @Test
    void aJobInHandIsNeverGivenTheRoomTroubleIs() {
        for (MascotAdvisor.Mood mood : MascotAdvisor.Mood.values()) {
            if (MascotAdvisor.ordinaryWork(mood)) {
                assertFalse(MascotSpeech.expanded(mood), mood + " is a job in hand, yet takes a third line");
            }
        }
        assertTrue(MascotSpeech.expanded(MascotAdvisor.Mood.HUNGRY), "hunger comes with an opening line");
        assertTrue(MascotSpeech.expanded(MascotAdvisor.Mood.DANGER), "danger comes with an opening line");
    }
}
