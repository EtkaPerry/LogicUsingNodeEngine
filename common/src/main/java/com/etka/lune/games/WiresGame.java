package com.etka.lune.games;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/**
 * One game of Redstone Wires: the board, its size, and the clock.
 *
 * <p>The clock runs only while the board is on screen, and only between the first turn and the
 * last. The game is played while Lune works, so looking away to watch her - closing the corner,
 * opening another page, going back to the world - must never cost the player their time. Each frame
 * the board is drawn adds the time since the one before it, capped, so a gap in the frames reads as
 * the player having looked away rather than as a very slow move.</p>
 */
public final class WiresGame {

    /** The longest gap between two frames that still counts as the player watching. */
    static final long MAX_FRAME_GAP = 250;
    /** How many repeats in a row are thrown back before a board is let through anyway. */
    static final int MAX_REDEALS = 64;

    /** What a turn did. */
    public enum Outcome { REFUSED, TURNED, SOLVED }

    /**
     * The game in progress.
     *
     * <p>Static on purpose: the panel is rebuilt every time it opens, and a half-lit board that
     * vanished whenever the player went to check on Lune would be a game nobody could finish.</p>
     */
    private static WiresGame current;

    private final WiresSize size;
    private final WiresBoard board;
    private long elapsed;
    /** When the board was last drawn, or 0 while it is out of sight. */
    private long lastWatched;
    private long solvedAt;
    private boolean newBest;

    WiresGame(WiresSize size, WiresBoard board) {
        this.size = size;
        this.board = board;
    }

    /** The game in progress, starting a small one if there has not been one yet. */
    public static WiresGame current() {
        if (current == null) {
            current = fresh(WiresSize.SMALL);
        }
        return current;
    }

    /** Whether a game has been started this session, without starting one to find out. */
    public static boolean exists() {
        return current != null;
    }

    /** Throws the game in progress away and deals a new board of {@code size}. */
    public static WiresGame startNew(WiresSize size) {
        current = fresh(size);
        return current;
    }

    /** A new board of {@code size}, one never dealt before, and remembered so it never is again. */
    private static WiresGame fresh(WiresSize size) {
        WiresBoard board = deal(size, ThreadLocalRandom.current()::nextLong,
                fingerprint -> WiresRecords.dealtBefore(size, fingerprint));
        WiresRecords.rememberDealt(size, board.fingerprint());
        return new WiresGame(size, board);
    }

    /**
     * A board of {@code size} whose circuit - turned and mirrored every way - is not one
     * {@code dealtBefore} remembers. A repeat is thrown back and another dealt.
     *
     * <p>With millions of circuits to a small board a repeat is rare, and a second one in a row
     * rarer still, so the cap only stops a board size with too few circuits from looping forever;
     * past it the last board is let through.</p>
     */
    static WiresBoard deal(WiresSize size, LongSupplier seeds, IntPredicate dealtBefore) {
        WiresBoard board = null;
        for (int attempt = 0; attempt < MAX_REDEALS; attempt++) {
            board = WiresBoard.generate(size.columns(), size.rows(), seeds.getAsLong());
            if (!dealtBefore.test(board.fingerprint())) {
                return board;
            }
        }
        return board;
    }

    public WiresSize size() {
        return size;
    }

    public WiresBoard board() {
        return board;
    }

    /** The first tile has been turned. */
    public boolean started() {
        return board.turns() > 0;
    }

    public boolean solved() {
        return board.solved();
    }

    /** Started and not solved: what a new board would throw away. */
    public boolean inProgress() {
        return started() && !solved();
    }

    /** One frame with the board on screen, at {@code now} in milliseconds. */
    public void watched(long now) {
        if (lastWatched > 0 && inProgress()) {
            elapsed += Math.clamp(now - lastWatched, 0, MAX_FRAME_GAP);
        }
        lastWatched = now;
    }

    /** The board went out of sight: the next frame starts the clock again rather than counting the gap. */
    public void unwatched() {
        lastWatched = 0;
    }

    /** Turns a tile, with the clock brought up to {@code now} first so the last move counts in full. */
    public Outcome turn(int tile, boolean clockwise, long now) {
        watched(now);
        if (!board.turn(tile, clockwise)) {
            return Outcome.REFUSED;
        }
        if (board.solved()) {
            solvedAt = now;
            return Outcome.SOLVED;
        }
        return Outcome.TURNED;
    }

    public boolean toggleLock(int tile) {
        return board.toggleLock(tile);
    }

    public long elapsedMillis() {
        return elapsed;
    }

    /** When the board was solved, on the clock {@link #turn} was given; 0 until then. */
    public long solvedAt() {
        return solvedAt;
    }

    /** Whether this clear beat an earlier one of the same size. */
    public boolean newBest() {
        return newBest;
    }

    public void markNewBest() {
        newBest = true;
    }

    /** "1:05", or "1:02:07" past the hour. Digits, so the same in every language. */
    public static String clock(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        return hours > 0
                ? hours + ":" + two(minutes) + ":" + two(rest)
                : minutes + ":" + two(rest);
    }

    private static String two(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
