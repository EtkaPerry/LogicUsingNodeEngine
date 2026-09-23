package com.etka.lune.games;

import com.etka.lune.config.BotConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fastest clear of each Redstone Wires size, how many boards of it were cleared, and every board
 * of it ever dealt, kept in {@code config/lune.json} beside the training course's record.
 *
 * <p>Keyed on {@link WiresSize#id()}, never on the label the player reads.</p>
 */
public final class WiresRecords {

    /**
     * Boards remembered per size before the oldest are let go: more than anyone plays, at a minute
     * or so a board, and 87 KB of config a size at the very most.
     */
    static final int MAX_REMEMBERED = 16_384;

    private WiresRecords() {}

    /** The fastest clear of {@code size} in milliseconds, or -1 before the first. */
    public static long best(WiresSize size) {
        Long best = bests().get(size.id());
        return best == null ? -1 : best;
    }

    public static int cleared(WiresSize size) {
        return cleared().getOrDefault(size.id(), 0);
    }

    public static int clearedTotal() {
        int total = 0;
        for (WiresSize size : WiresSize.values()) {
            total += cleared(size);
        }
        return total;
    }

    /**
     * Records a clear and writes it out. True when it beat an earlier clear of the same size; the
     * first clear of a size sets the record without beating one.
     */
    public static boolean record(WiresSize size, long millis) {
        boolean beaten = record(bests(), cleared(), size.id(), millis);
        BotConfig.get().save();
        return beaten;
    }

    /** The rule itself, free of the config, so the tests can hold it to account. */
    static boolean record(Map<String, Long> bests, Map<String, Integer> cleared, String id, long millis) {
        cleared.merge(id, 1, Integer::sum);
        Long before = bests.get(id);
        if (before == null || millis < before) {
            bests.put(id, millis);
            return before != null;
        }
        return false;
    }

    // --- boards dealt ----------------------------------------------------------

    /** Whether a board with this {@link WiresBoard#fingerprint()} has been dealt at {@code size}. */
    public static boolean dealtBefore(WiresSize size, int fingerprint) {
        return DealtHistory.contains(dealt().get(size.id()), fingerprint);
    }

    /** Remembers a board as dealt, so it is never dealt again, and writes it out. */
    public static void rememberDealt(WiresSize size, int fingerprint) {
        dealt().put(size.id(), DealtHistory.appended(dealt().get(size.id()), fingerprint, MAX_REMEMBERED));
        BotConfig.get().save();
    }

    // --- the config ------------------------------------------------------------

    private static Map<String, String> dealt() {
        BotConfig config = BotConfig.get();
        if (config.wiresDealt == null) {
            config.wiresDealt = new LinkedHashMap<>();
        }
        return config.wiresDealt;
    }

    /** Tolerates a config written before these fields existed, or hand-edited to null. */
    private static Map<String, Long> bests() {
        BotConfig config = BotConfig.get();
        if (config.wiresBest == null) {
            config.wiresBest = new LinkedHashMap<>();
        }
        return config.wiresBest;
    }

    private static Map<String, Integer> cleared() {
        BotConfig config = BotConfig.get();
        if (config.wiresCleared == null) {
            config.wiresCleared = new LinkedHashMap<>();
        }
        return config.wiresCleared;
    }
}
