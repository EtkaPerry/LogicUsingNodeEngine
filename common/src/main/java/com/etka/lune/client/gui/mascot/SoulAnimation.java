package com.etka.lune.client.gui.mascot;

import java.util.List;

/**
 * Picks the row of the soul atlas that shows Lune's mood, and wipes one form into the next.
 *
 * <p>The soul is Lune's whole expression. At rest it is a pool in the bottom of her head; when
 * something is on her mind the pool reshapes itself - a lamp over still water when she is paused,
 * bubbles when she is thinking, a wall when she is blocked - and nothing is drawn outside the head.
 * Every mood is a row of {@code soul.png}, in the order the {@code ROW_*} constants below fix. A
 * change of mood does not cut between rows: a pool drains from the top to reveal the new shape, a
 * pool rises from the bottom to swallow the old one, and between two pools the surface itself
 * moves, sinking to a lower level or climbing to a higher one.</p>
 *
 * <p>The forms follow one rule: a soul that moves is Lune doing something, and a soul that is
 * broken, drained or blown about is something being done to her. That is why fighting is a cut she
 * makes and being hurt is a crack she carries.</p>
 *
 * <p>Pure, so the timing can be tested without a client.</p>
 */
public final class SoulAnimation {

    public static final int FRAMES = 8;
    public static final int ROWS = 32;

    static final int ROW_IDLE = 0;
    static final int ROW_WORKING = 1;
    static final int ROW_THINKING = 2;
    static final int ROW_ASKING = 3;
    static final int ROW_QUIET = 4;
    static final int ROW_RESTING = 5;
    static final int ROW_PAUSED = 6;
    static final int ROW_WAITING = 7;
    static final int ROW_LOADING = 8;
    static final int ROW_SEARCH = 9;
    static final int ROW_TRAVEL = 10;
    static final int ROW_BRIDGING = 11;
    static final int ROW_PILLARING = 12;
    static final int ROW_STAIRS = 13;
    static final int ROW_FRAMING = 14;
    static final int ROW_DIVING = 15;
    static final int ROW_SWIMMING = 16;
    static final int ROW_ASCENDING = 17;
    static final int ROW_LEARNING = 18;
    static final int ROW_STALLED = 19;
    static final int ROW_BLOCKED = 20;
    static final int ROW_DANGER = 21;
    static final int ROW_FIGHT = 22;
    static final int ROW_FLEE = 23;
    static final int ROW_HURT = 24;
    static final int ROW_RECOVERING = 25;
    static final int ROW_EFFECT = 26;
    static final int ROW_HUNGRY = 27;
    static final int ROW_DEAD = 28;
    static final int ROW_SUCCESS = 29;
    static final int ROW_INVENTORY_FULL = 30;
    static final int ROW_MISSING_MATERIALS = 31;

    /** How long one form takes to give way to the next. */
    static final long WIPE_MILLIS = 320L;

    /**
     * One atlas cell to draw. Only the part of the head between {@code from} and {@code to} is
     * shown, as fractions of the head's height where 0 is the top and 1 the bottom.
     */
    public record Layer(int row, int frame, float from, float to) {
        public boolean clipped() {
            return from > 0f || to < 1f;
        }
    }

    private int shownRow = -1;
    private int previousRow = -1;
    private long wipeStartedAt;
    private boolean wipeDown;

    public static int row(MascotAdvisor.Mood mood) {
        return switch (mood) {
            case IDLE -> ROW_IDLE;
            case WORKING -> ROW_WORKING;
            case THINKING -> ROW_THINKING;
            case ASKING -> ROW_ASKING;
            case QUIET -> ROW_QUIET;
            case RESTING -> ROW_RESTING;
            case PAUSED -> ROW_PAUSED;
            case WAITING -> ROW_WAITING;
            case LOADING -> ROW_LOADING;
            case SEARCH -> ROW_SEARCH;
            case TRAVEL -> ROW_TRAVEL;
            case BRIDGING -> ROW_BRIDGING;
            case PILLARING -> ROW_PILLARING;
            case STAIRS -> ROW_STAIRS;
            case FRAMING -> ROW_FRAMING;
            case DIVING -> ROW_DIVING;
            case SWIMMING -> ROW_SWIMMING;
            case ASCENDING -> ROW_ASCENDING;
            case LEARNING -> ROW_LEARNING;
            case STALLED -> ROW_STALLED;
            case BLOCKED -> ROW_BLOCKED;
            case DANGER -> ROW_DANGER;
            case FIGHT -> ROW_FIGHT;
            case FLEE -> ROW_FLEE;
            case HURT -> ROW_HURT;
            case RECOVERING -> ROW_RECOVERING;
            case EFFECT -> ROW_EFFECT;
            case HUNGRY -> ROW_HUNGRY;
            case DEAD -> ROW_DEAD;
            case SUCCESS -> ROW_SUCCESS;
            case INVENTORY_FULL -> ROW_INVENTORY_FULL;
            case MISSING_MATERIALS -> ROW_MISSING_MATERIALS;
        };
    }

    /**
     * Rows where the soul is still a body of liquid. A pool rises into place; a shape is uncovered.
     *
     * <p>Listed the short way round - the rows that are <em>not</em> pools are the few where the
     * soul has left the bottom of the head entirely.</p>
     */
    static boolean pool(int row) {
        return switch (row) {
            case ROW_ASKING, ROW_WAITING, ROW_LOADING, ROW_DIVING, ROW_BLOCKED, ROW_DANGER,
                    ROW_DEAD, ROW_MISSING_MATERIALS -> false;
            default -> true;
        };
    }

    /**
     * The row of the 96 px cell where a pool's surface lies, measured off {@code soul.png}; what
     * stands above it (a lamp, bubbles, a pillar) is not the pool. Only meaningful for rows where
     * {@link #pool(int)} holds, and it decides which way two pools wipe: a pool drains down to a
     * lower surface, because rising into one would eat the old pool from underneath and leave its
     * top hanging in the air until it vanished.
     */
    static int surface(int row) {
        return switch (row) {
            case ROW_SWIMMING, ROW_SUCCESS -> 17;
            case ROW_INVENTORY_FULL -> 21;
            case ROW_QUIET -> 27;
            case ROW_THINKING -> 31;
            case ROW_WORKING -> 34;
            case ROW_IDLE -> 35;
            case ROW_HURT -> 47;
            case ROW_FRAMING -> 56;
            case ROW_RESTING, ROW_RECOVERING -> 57;
            case ROW_TRAVEL -> 63;
            case ROW_PAUSED, ROW_EFFECT -> 65;
            case ROW_STALLED -> 66;
            case ROW_HUNGRY -> 71;
            case ROW_SEARCH, ROW_BRIDGING, ROW_FIGHT -> 72;
            case ROW_STAIRS -> 73;
            case ROW_LEARNING -> 74;
            case ROW_FLEE -> 75;
            case ROW_PILLARING -> 78;
            case ROW_ASCENDING -> 79;
            // Not a pool, or not measured yet: an empty head, so wipes drain into it and rise out.
            default -> 84;
        };
    }

    /** Moods where the renderer paints her lid shut over whatever the row draws. */
    static boolean eyeShut(MascotAdvisor.Mood mood) {
        return mood == MascotAdvisor.Mood.QUIET || mood == MascotAdvisor.Mood.RESTING
                || mood == MascotAdvisor.Mood.DEAD;
    }

    /** Milliseconds per atlas frame: slow for a resting pool, quick for flames and spinners. */
    public static int frameMillis(int row) {
        return switch (row) {
            case ROW_IDLE -> 720;
            case ROW_WORKING -> 480;
            case ROW_THINKING -> 400;
            case ROW_ASKING -> 560;
            case ROW_QUIET -> 380;
            case ROW_RESTING -> 900;
            case ROW_PAUSED -> 560;
            case ROW_WAITING -> 560;
            case ROW_LOADING -> 140;
            case ROW_SEARCH -> 300;
            case ROW_TRAVEL -> 260;
            case ROW_BRIDGING, ROW_PILLARING, ROW_FRAMING -> 380;
            case ROW_STAIRS -> 420;
            case ROW_DIVING -> 160;
            case ROW_SWIMMING -> 320;
            case ROW_ASCENDING -> 260;
            case ROW_LEARNING -> 360;
            case ROW_STALLED, ROW_BLOCKED -> 300;
            case ROW_DANGER -> 110;
            case ROW_FIGHT -> 240;
            case ROW_FLEE -> 220;
            case ROW_HURT, ROW_EFFECT, ROW_HUNGRY -> 380;
            case ROW_RECOVERING -> 520;
            case ROW_DEAD -> 900;
            case ROW_SUCCESS -> 260;
            case ROW_INVENTORY_FULL -> 500;
            default -> 380;
        };
    }

    public static int frame(int row, long now) {
        return (int) ((now / frameMillis(row)) % FRAMES);
    }

    /** The cells to draw for this mood at this moment, bottom layer first. */
    public List<Layer> layers(MascotAdvisor.Mood mood, long now) {
        int target = row(mood);
        if (shownRow < 0) {
            shownRow = target;
        } else if (target != shownRow) {
            previousRow = shownRow;
            shownRow = target;
            wipeStartedAt = now;
            wipeDown = !pool(target)
                    || (pool(previousRow) && surface(target) > surface(previousRow));
        }
        float progress = previousRow < 0 ? 1f : (now - wipeStartedAt) / (float) WIPE_MILLIS;
        if (progress >= 1f) {
            previousRow = -1;
            return List.of(new Layer(shownRow, frame(shownRow, now), 0f, 1f));
        }
        float line = progress * progress * (3f - 2f * progress);
        if (wipeDown) {
            return List.of(
                    new Layer(previousRow, frame(previousRow, now), line, 1f),
                    new Layer(shownRow, frame(shownRow, now), 0f, line));
        }
        return List.of(
                new Layer(previousRow, frame(previousRow, now), 0f, 1f - line),
                new Layer(shownRow, frame(shownRow, now), 1f - line, 1f));
    }
}
