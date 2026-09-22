package com.etka.lune.bot.util;

import java.util.List;

/**
 * What the sky is doing, as a Check Weather card asks about it.
 *
 * <p>A card of its own rather than another Check Player value, for the reason the Check Time card
 * exists: weather is not a number. "Weather at least 2" would compare correctly and mean nothing
 * to the person reading the card, so the question is a name and the answer is Success or Fail.</p>
 *
 * <p>Two things about the answer are worth knowing before wiring one up:</p>
 *
 * <ul>
 *   <li><b>{@link #RAIN} is true during a thunderstorm</b>, because it is raining during a
 *       thunderstorm. A job that wants the storm and not the drizzle asks for
 *       {@link #THUNDER}.</li>
 *   <li><b>This is the world's weather, not the view out of the window.</b> Rain falls on a forest
 *       and not on the desert beside it, and never in the Nether; the card still reports what the
 *       sky is set to, which is what the mobs, the crops and a bed's refusal all follow.</li>
 * </ul>
 *
 * <p>The labels are identifiers written into saved tasks, so they stay English and only their
 * rendering is translated - the same rule every dropdown value here follows.</p>
 */
public enum Weather {

    CLEAR("Clear"),
    RAIN("Raining"),
    THUNDER("Thundering");

    private final String label;

    Weather(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Names for a picker, driest first. */
    public static List<String> labels() {
        return List.of(CLEAR.label, RAIN.label, THUNDER.label);
    }

    /** Reads back a name from {@link #labels()}; null when a stored task names something else. */
    public static Weather fromLabel(String label) {
        for (Weather weather : values()) {
            if (weather.label.equalsIgnoreCase(label)) {
                return weather;
            }
        }
        return null;
    }

    /** Whether this is the weather the world is having. */
    public boolean matches(boolean raining, boolean thundering) {
        return switch (this) {
            case CLEAR -> !raining;
            case RAIN -> raining;
            case THUNDER -> thundering;
        };
    }

    /** The strongest of the three that is true, which is the one to name in a status line. */
    public static Weather of(boolean raining, boolean thundering) {
        return thundering ? THUNDER : raining ? RAIN : CLEAR;
    }
}
