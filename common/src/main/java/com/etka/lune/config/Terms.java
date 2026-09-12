package com.etka.lune.config;

import com.etka.lune.bot.AutoRun;

import java.util.List;

/**
 * What the player has to have read before Lune will play the game for them.
 *
 * <p>Lune presses the keys. That is a different kind of mod from one that draws a minimap: it is
 * against the rules on most multiplayer servers, and the punishment for it lands on the player's
 * account rather than on the author's. It also digs, and it will dig somewhere the player did not
 * mean it to. Neither of those is a surprise worth springing on someone who has just installed a
 * jar, so the panel does not open and no task starts until the three points below have been ticked
 * and accepted.</p>
 *
 * <p>Asked at the panel and nowhere else. A player who has not reached for Lune yet has agreed to
 * nothing and needs to agree to nothing, and a mod that greets a world with a wall of text has
 * mistaken its own installation for an event in someone else's game.</p>
 *
 * <p>The wording and the version live here rather than in the screen because they belong together:
 * bumping {@link #VERSION} is the act of saying the wording changed enough to ask again, and
 * anything that consults the acceptance state - the keybind, the engine, the Config tab - reads one
 * class rather than reaching into the config for a number whose meaning is somewhere else.</p>
 */
public final class Terms {

    /**
     * The version of the wording below.
     *
     * <p>Bump it when a point changes materially, and everybody is asked again the next time they
     * open the panel. Do not bump it for a typo: an acceptance the player already gave is the thing
     * being kept, and asking for it twice over a comma teaches them to click through the screen
     * without reading.</p>
     */
    public static final int VERSION = 1;

    /** One thing the player is agreeing to: a short label to tick, and the sentence it means. */
    public record Point(String label, String detail) {}

    public static final String TITLE = "Before Lune plays for you";

    /**
     * The whole page is written to be short, and the labels carry the meaning.
     *
     * <p>Two pressures point the same way. The screen has to fit the smallest window the game will
     * scale to, and terms that run off the bottom edge are terms nobody read. And a page dense
     * enough to look like paperwork gets skimmed, which is the same failure by a slower route: the
     * one line under each box is there to be read, so it is one line.</p>
     */
    public static final String INTRO =
            "Lune presses the keys for you. That is against the rules on most servers, and she digs "
                    + "in a world you may not have backed up.";

    public static final List<Point> POINTS = List.of(
            new Point("The server rules are mine to check",
                    "I have read the rules where I play, and a ban there is mine to carry."),
            new Point("I will use her fairly",
                    "No griefing, harassing or stealing, and I stop where I am asked to."),
            new Point("I accept the license, and the risk",
                    "The Lune Personal Use License. No warranty; a lost world is mine."));

    /** Shown under the boxes, so the two buttons are not the only explanation of what happens next. */
    public static final String FOOTNOTE =
            "Until you accept, the panel stays shut and Lune runs nothing. "
                    + "About -> Review terms brings this back.";

    private Terms() {}

    // --- state ---------------------------------------------------------------

    /** Whether the current wording has been accepted. */
    public static boolean accepted() {
        return accepted(BotConfig.get().acceptedTermsVersion);
    }

    /** The rule itself, apart from where the number is stored, so it can be tested on its own. */
    public static boolean accepted(int acceptedVersion) {
        return acceptedVersion >= VERSION;
    }

    /**
     * Whether a task may start at all.
     *
     * <p>The panel is the only way a player starts anything and it will not open unaccepted, so
     * this is a second lock on the same door. It is here so the promise stays true for a caller
     * that does not go through the panel - including one written later.</p>
     */
    public static boolean mayRun() {
        return accepted() || unattended();
    }

    /**
     * Whether this client was launched as a test run rather than by a player.
     *
     * <p>The harness is the one thing that runs without accepting: it has nobody to ask and no
     * screen to ask on, and it only starts when someone passed {@code -Dlune.autorun.task=...} on
     * their own command line, which is that person accepting in the only place they can. A modal
     * on top of it would also be the end of every overnight benchmark.</p>
     */
    private static boolean unattended() {
        return AutoRun.isConfigured() || AutoRun.isRecordingSession();
    }

    // --- transitions ---------------------------------------------------------

    /**
     * Records acceptance of the current wording and writes it straight out.
     *
     * <p>Saved here rather than on the next config write, which happens when the panel closes: what
     * the player agreed to should survive a crash, and the panel they are about to open is a place
     * people leave by closing the game.</p>
     */
    public static void accept() {
        BotConfig config = BotConfig.get();
        config.acceptedTermsVersion = VERSION;
        config.save();
    }
}
