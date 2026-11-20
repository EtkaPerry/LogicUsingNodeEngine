package com.etka.lune.share;

import com.etka.lune.util.Lang;

/**
 * How long a shared link lives after its last open, picked in the Share popup and sent to the
 * service with the upload. The service deletes a share once it has stayed unopened for this
 * long; links made before there was a choice keep {@link #MONTH}, the thirty days shares were
 * always promised.
 *
 * <p>The choices are a fixed ring rather than a free number because the service keeps only the
 * durations it knows ({@code KEEP_SECONDS} in the site's worker), so a build cannot ask for a
 * year.</p>
 */
public enum ShareKeep {
    HOUR(3_600, "lune.gui.share.keep.hour"),
    DAY(86_400, "lune.gui.share.keep.day"),
    THREE_DAYS(259_200, "lune.gui.share.keep.three_days"),
    WEEK(604_800, "lune.gui.share.keep.week"),
    MONTH(2_592_000, "lune.gui.share.keep.month"),
    THREE_MONTHS(7_776_000, "lune.gui.share.keep.three_months");

    /** What a share gets when nobody picked a keep time for it. */
    public static final ShareKeep DEFAULT = MONTH;

    private final int seconds;
    private final String langKey;

    ShareKeep(int seconds, String langKey) {
        this.seconds = seconds;
        this.langKey = langKey;
    }

    public int seconds() {
        return seconds;
    }

    /** The duration as the player reads it, on the keep button and inside sentences. */
    public String label() {
        return Lang.get(langKey);
    }

    /** The next choice round the ring, for the button that steps through them. */
    public ShareKeep next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
