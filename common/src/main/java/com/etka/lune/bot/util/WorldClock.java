package com.etka.lune.bot.util;

import net.minecraft.client.multiplayer.ClientLevel;

import java.util.List;
import java.util.Locale;

/**
 * Where the overworld clock is in its twenty-four thousand tick day.
 *
 * <p>"Is it night?" had three answers in this codebase and no shared one: the mascot compared raw
 * ticks against a pair of literals, {@link com.etka.lune.bot.task.SleepTask} asked the bed whether
 * it would accept, and a task had no way to ask at all. One definition here means a Check Time card
 * that says "dark enough to sleep" and a Sleep card that then refuses are impossible.</p>
 *
 * <p>The clock read is always the overworld's, even from the Nether. A bot standing in a fortress
 * planning its trip home cares what time it is up top, and a dimension with no sky of its own has
 * no more useful answer to give.</p>
 */
public final class WorldClock {

    public static final long TICKS_PER_DAY = 24_000L;

    /** Tick zero is sunrise, so the wall clock the player reads is six hours ahead of it. */
    private static final long SUNRISE_HOUR = 6L;
    private static final long TICKS_PER_HOUR = 1_000L;

    private WorldClock() {}

    /**
     * A stretch of the day a task can ask about.
     *
     * <p>The first four tile the day end to end. {@link #BEDTIME} deliberately overlaps them: it is
     * vanilla's own rule for when a bed may be used, which spans the end of dusk, all of night and
     * most of dawn, and it is the question a bot actually needs answered before walking home.</p>
     */
    public enum Phase {
        DAY("Day", 0L, 11_999L),
        DUSK("Dusk", 12_000L, 13_799L),
        NIGHT("Night", 13_800L, 22_199L),
        DAWN("Dawn", 22_200L, 23_999L),
        BEDTIME("Dark enough to sleep", 12_542L, 23_460L);

        private final String label;
        private final long from;
        private final long to;

        Phase(String label, long from, long to) {
            this.label = label;
            this.from = from;
            this.to = to;
        }

        public String label() {
            return label;
        }

        public long from() {
            return from;
        }

        public long to() {
            return to;
        }

        public boolean matches(long dayTime) {
            long time = normalise(dayTime);
            return time >= from && time <= to;
        }
    }

    /** The four that tile the day, in the order they happen. */
    private static final List<Phase> BANDS =
            List.of(Phase.DAY, Phase.DUSK, Phase.NIGHT, Phase.DAWN);

    /** Names for a picker, bands first and the bed rule last. */
    public static List<String> phaseNames() {
        return List.of(Phase.DAY.label, Phase.DUSK.label, Phase.NIGHT.label, Phase.DAWN.label,
                Phase.BEDTIME.label);
    }

    /** Reads back a name from {@link #phaseNames()}; null when a stored task names something else. */
    public static Phase fromLabel(String label) {
        for (Phase phase : Phase.values()) {
            if (phase.label.equalsIgnoreCase(label)) {
                return phase;
            }
        }
        return null;
    }

    /** The overworld time of day in ticks, always in {@code [0, 24000)}. */
    public static long dayTime(ClientLevel level) {
        return level == null ? 0L : normalise(level.getOverworldClockTime());
    }

    /** Which of the four tiling bands this tick falls in; never null. */
    public static Phase bandOf(long dayTime) {
        long time = normalise(dayTime);
        for (Phase band : BANDS) {
            if (band.matches(time)) {
                return band;
            }
        }
        // Unreachable while the bands tile the day, and a sane answer if one is ever retuned.
        return Phase.DAY;
    }

    /** True while a bed would accept the player, which is what "night" means to an unattended bot. */
    public static boolean isNight(long dayTime) {
        return Phase.BEDTIME.matches(dayTime);
    }

    /** The band plus the wall clock a player would read, e.g. {@code "Dusk (18:24)"}. */
    public static String describe(long dayTime) {
        return bandOf(dayTime).label() + " (" + clock(dayTime) + ")";
    }

    /** The in-game time as a 24-hour clock, where tick zero is 06:00. */
    public static String clock(long dayTime) {
        return clockOf(minuteOfDay(dayTime));
    }

    /**
     * The in-game time as minutes since midnight, where tick zero is 06:00.
     *
     * <p>The number behind {@link #clock}, split out because a card comparing against 20:00 needs
     * the value rather than the rendering, and two ways of working out what o'clock it is would
     * eventually disagree by a minute.</p>
     */
    public static long minuteOfDay(long dayTime) {
        long time = normalise(dayTime);
        long hour = Math.floorMod(time / TICKS_PER_HOUR + SUNRISE_HOUR, 24L);
        long minute = time % TICKS_PER_HOUR * 60L / TICKS_PER_HOUR;
        return hour * 60L + minute;
    }

    /** Minutes since midnight written as a 24-hour clock, for whichever clock supplied them. */
    public static String clockOf(long minuteOfDay) {
        long minutes = Math.floorMod(minuteOfDay, 24L * 60L);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60L, minutes % 60L);
    }

    private static long normalise(long dayTime) {
        return Math.floorMod(dayTime, TICKS_PER_DAY);
    }
}
