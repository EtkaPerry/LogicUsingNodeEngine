package com.etka.lune.util;

import com.etka.lune.config.BotConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tone of an alert is an identifier written into a saved task, so what matters here is that it
 * survives the round trip and that nothing can be added to the enum without reaching the dropdown.
 */
class AlertsTest {

    @BeforeAll
    static void bootstrapRegistries() {
        // Tone names a SoundEvent per constant, and those only exist once the registries are up.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void everyToneIsOfferedInTheDropdown() {
        List<String> offered = Alerts.Tone.labels();
        assertEquals(Alerts.Tone.values().length, offered.size(),
                "a tone that is not in labels() cannot be picked: " + offered);
        for (Alerts.Tone tone : Alerts.Tone.values()) {
            assertTrue(offered.contains(tone.label()), tone + " is missing from the dropdown");
        }
    }

    @Test
    void aSavedToneComesBackAsItself() {
        for (Alerts.Tone tone : Alerts.Tone.values()) {
            assertEquals(tone, Alerts.Tone.fromLabel(tone.label()));
        }
        assertEquals(Alerts.Tone.CHIME, Alerts.Tone.fromLabel("  chime "));
    }

    /**
     * An Always source with no interval set pulses every tick. A Notify card behind one raises
     * twenty alerts a second, and twenty overlapping bells is not a louder alert - it is a drone.
     */
    @Test
    void alertsRaisedTooCloseTogetherDoNotSoundTwice() {
        assertTrue(Alerts.mayInterrupt(Long.MIN_VALUE, 0L), "the first alert always sounds");
        assertTrue(Alerts.mayInterrupt(Long.MIN_VALUE, System.currentTimeMillis()));
        assertTrue(Alerts.mayInterrupt(0L, Alerts.QUIET_MS), "the gap is inclusive");
        assertTrue(Alerts.mayInterrupt(0L, Alerts.QUIET_MS * 3));
        // A clock resynced backwards must not go quiet for the rest of the session.
        assertTrue(Alerts.mayInterrupt(10_000L, 1_000L));
        // A tick later, which is what a continuous Always circuit asks for.
        assertFalse(Alerts.mayInterrupt(0L, 50L));
        assertFalse(Alerts.mayInterrupt(0L, Alerts.QUIET_MS - 1));
    }

    @Test
    void volumeIsAPercentageTheSoundEngineWillTake() {
        assertEquals(1.0F, Alerts.volume(100));
        assertEquals(0.5F, Alerts.volume(50));
        assertEquals(0.0F, Alerts.volume(0));
        // Vanilla clamps an instance volume to one before the player's own sliders touch it, so
        // anything above the cap is not a louder alert and must not be let through as if it were.
        assertEquals(1.0F, Alerts.volume(500));
        assertEquals(0.0F, Alerts.volume(-20));
    }

    /**
     * The point of having three tone settings rather than one: from another window the sound is
     * the whole message, so the three things worth walking back for must not arrive alike.
     */
    @Test
    void theThreeAutomaticAlertsSoundDifferentOutOfTheBox() {
        BotConfig shipped = new BotConfig();
        Set<String> tones = new HashSet<>(List.of(shipped.alertToneFinished,
                shipped.alertToneFailed, shipped.alertToneDeath));
        assertEquals(3, tones.size(), "shipped tones are not all different: " + tones);
        for (String tone : tones) {
            assertEquals(tone, Alerts.Tone.fromLabel(tone).label(), "not a tone that exists");
            assertTrue(Alerts.Tone.labels().contains(tone));
        }
    }

    @Test
    void anUnreadableToneRingsRatherThanFailingTheRun() {
        // A task saved by a newer build, or edited by hand. Losing the sound is a small thing;
        // failing the card the player wired in to be told about something is not.
        assertEquals(Alerts.Tone.BELL, Alerts.Tone.fromLabel("Gong"));
        assertEquals(Alerts.Tone.BELL, Alerts.Tone.fromLabel(""));
        assertEquals(Alerts.Tone.BELL, Alerts.Tone.fromLabel(null));
    }
}
