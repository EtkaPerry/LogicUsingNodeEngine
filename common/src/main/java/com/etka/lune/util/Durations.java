package com.etka.lune.util;

/**
 * How long something took, or how long is left, written the way a person reads a clock.
 *
 * <p>Lifted out of the Main tab when the Countdown card needed the same sentence. Two formatters
 * would have agreed for an afternoon and then disagreed the first time either grew a unit - which
 * is exactly what happened: the run timer stopped at hours, so a countdown set for two days would
 * have read "51h 0m 0s" beside a card that says "Days" on it.</p>
 *
 * <p>Three parts at most, and only the ones that have something in them. The seconds are dropped
 * once the total is measured in days, because nobody waiting two days is watching them.</p>
 */
public final class Durations {

    private static final long SECONDS_PER_DAY = 86_400L;
    private static final long SECONDS_PER_HOUR = 3_600L;
    private static final long TICKS_PER_SECOND = 20L;

    private Durations() {}

    /** A whole number of seconds as words, e.g. {@code "1d 4h 20m"}, {@code "2h 13m 5s"}, {@code "45s"}. */
    public static String describe(long seconds) {
        long total = Math.max(0L, seconds);
        long days = total / SECONDS_PER_DAY;
        long hours = total % SECONDS_PER_DAY / SECONDS_PER_HOUR;
        long minutes = total % SECONDS_PER_HOUR / 60L;
        if (days > 0L) {
            return Lang.get("lune.duration.days", days, hours, minutes);
        }
        if (hours > 0L) {
            return Lang.get("lune.duration.hours", hours, minutes, total % 60L);
        }
        if (minutes > 0L) {
            return Lang.get("lune.duration.minutes", minutes, total % 60L);
        }
        return Lang.get("lune.duration.seconds", total);
    }

    /** The same, for a count of game ticks. */
    public static String ofTicks(long ticks) {
        return describe(Math.max(0L, ticks) / TICKS_PER_SECOND);
    }
}
