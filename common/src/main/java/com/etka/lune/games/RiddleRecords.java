package com.etka.lune.games;

import com.etka.lune.config.BotConfig;

/**
 * How many riddles have been solved, how many of those without a hint, and every recipe asked,
 * kept in {@code config/lune.json} beside the rest of the games' records.
 */
public final class RiddleRecords {

    /** Recipes remembered before the oldest are let go: more than most packs hold. */
    static final int MAX_REMEMBERED = 8_192;

    private RiddleRecords() {}

    public static int solved() {
        return BotConfig.get().riddlesSolved;
    }

    public static int solvedClean() {
        return BotConfig.get().riddlesSolvedClean;
    }

    /** Counts a solved riddle, and writes it out. */
    public static void recordSolved(boolean withoutHints) {
        BotConfig config = BotConfig.get();
        config.riddlesSolved++;
        if (withoutHints) {
            config.riddlesSolvedClean++;
        }
        config.save();
    }

    public static boolean dealtBefore(int fingerprint) {
        return DealtHistory.contains(BotConfig.get().riddlesDealt, fingerprint);
    }

    public static void rememberDealt(int fingerprint) {
        BotConfig config = BotConfig.get();
        config.riddlesDealt = DealtHistory.appended(config.riddlesDealt, fingerprint, MAX_REMEMBERED);
        config.save();
    }

    /** Every recipe has been asked: the record starts over, so the next round is new again. */
    public static void forgetDealt() {
        BotConfig config = BotConfig.get();
        config.riddlesDealt = "";
        config.save();
    }
}
