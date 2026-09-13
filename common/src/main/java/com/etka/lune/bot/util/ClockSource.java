package com.etka.lune.bot.util;

import net.minecraft.client.multiplayer.ClientLevel;

import java.time.LocalTime;
import java.util.List;

/**
 * Which clock a card means when it asks what the time is.
 *
 * <p>"Until eight in the evening" has two honest answers and they are rarely the same hour, so the
 * player picks which one they meant:</p>
 *
 * <ul>
 *   <li>{@link #GAME} - the world's own twenty-four hour day, the one {@link WorldClock} reads.
 *       On a server this is the server's clock in the sense that matters: every player on it sees
 *       the same hour, and it is what "come back at dusk" means to the people playing there.</li>
 *   <li>{@link #SYSTEM} - the clock on the computer Minecraft is running on, in its own time zone.
 *       This is what an event announced for 20:00 means, and it keeps running at the same speed
 *       whatever the world is doing.</li>
 * </ul>
 *
 * <p>There is deliberately no third option for the server's wall clock. A Minecraft server never
 * tells the client what time it is where it lives - only the world time is on the wire - so an
 * option promising it would be a made-up number wearing an authoritative name. A player whose
 * server sits in another time zone wants {@link #SYSTEM} with the hour converted, which is a sum
 * they can do and Lune cannot.</p>
 *
 * <p>The labels are identifiers written into saved tasks, so they stay English and only their
 * rendering is translated - the same rule every dropdown value here follows.</p>
 */
public enum ClockSource {

    SYSTEM("System clock"),
    GAME("Game clock");

    private final String label;

    ClockSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Names for a picker, in the order they are offered. */
    public static List<String> labels() {
        return List.of(SYSTEM.label, GAME.label);
    }

    /** Reads back a name from {@link #labels()}; falls back to the system clock for junk. */
    public static ClockSource fromLabel(String label) {
        for (ClockSource source : values()) {
            if (source.label.equalsIgnoreCase(label)) {
                return source;
            }
        }
        return SYSTEM;
    }

    /**
     * What this clock reads now, as minutes since midnight in {@code [0, 1440)}.
     *
     * @param level the client level, used only by {@link #GAME}; null reads as midnight
     */
    public long minuteOfDay(ClientLevel level) {
        if (this == GAME) {
            return WorldClock.minuteOfDay(WorldClock.dayTime(level));
        }
        LocalTime now = LocalTime.now();
        return now.getHour() * 60L + now.getMinute();
    }
}
