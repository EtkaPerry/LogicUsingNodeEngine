package com.etka.lune.bot.util;

/**
 * Bookkeeping for a search that is spread over many ticks, considers candidates in distance order,
 * and whose verdict on a candidate depends on where the head happens to be pointing.
 * <p>
 * Those three facts do not combine on their own. A sweep that simply remembered how far it had got
 * and returned the first acceptance would answer "which block do I mine" with "whichever one your
 * head happened to be facing on the tick its turn came up" - which is how a bot cuts two logs and
 * then walks past the rest of the tree to one across the field, and how it mines one block, turns
 * round for the one behind it, and takes the long way to both.
 * <p>
 * So this separates the two kinds of "no". A candidate that is ruled out is done with. A candidate
 * that was only <em>postponed</em> - the head is elsewhere, and the head is still turning - has not
 * been answered at all, and the sweep goes back for it. The result is the nearest acceptance rather
 * than the first one stumbled across, held until everything closer has been resolved one way or the
 * other.
 * <p>
 * Kept free of world types on purpose: it is pure index arithmetic, and the arithmetic is the part
 * that is easy to get wrong.
 */
public final class SweepCursor {

    /**
     * How many times a sweep goes back for postponed candidates before settling for what it has.
     * <p>
     * The head is usually turning while this runs, so a second look normally resolves them. But
     * nothing guarantees it ever gets there - a scan can finish its views, or the caller may not be
     * turning at all - and a sweep that waits forever for an answer that is not coming reports
     * "still looking" for the rest of the task.
     */
    public static final int MAX_POSTPONE_PASSES = 2;

    /** What the caller should do once its slice of work for this tick has run out. */
    public enum Outcome {
        /** Mid-list, or going back for postponed candidates. Ask again next tick. */
        KEEP_SWEEPING,
        /** {@link #accepted()} is the nearest acceptable candidate. Take it. */
        COMMIT,
        /** Everything has been looked at and none of it was acceptable. */
        NOTHING
    }

    private int size;
    private int cursor;
    private int accepted = -1;
    private int postponed = -1;
    private int passes;

    /** Starts a fresh sweep over a list of {@code size} candidates in distance order. */
    public void reset(int size) {
        this.size = Math.max(0, size);
        this.cursor = 0;
        this.accepted = -1;
        this.postponed = -1;
        this.passes = 0;
    }

    /**
     * Whether a candidate remains that could still beat the one already held.
     * <p>
     * Candidates are in distance order, so once something is accepted nothing after it can be
     * nearer and examining the rest cannot change the answer.
     */
    public boolean hasNext() {
        return cursor < size && (accepted < 0 || cursor <= accepted);
    }

    /** The next candidate's index. Only meaningful when {@link #hasNext()} is true. */
    public int next() {
        return cursor++;
    }

    /** Records an acceptable candidate. Later calls win only if they are nearer. */
    public void accept(int index) {
        if (accepted < 0 || index < accepted) {
            accepted = index;
        }
    }

    /** Whether a postponed candidate is already outstanding, so the next one need not be tested. */
    public boolean hasPostponement() {
        return postponed >= 0;
    }

    /**
     * Records that the only objection to a candidate was where the head currently points. Sweeps run
     * in ascending order, so the first one recorded is the nearest still outstanding.
     */
    public void postpone(int index) {
        if (postponed < 0) {
            postponed = index;
        }
    }

    public int accepted() {
        return accepted;
    }

    /** Closes off this tick's slice and says what the caller should do. */
    public Outcome finish() {
        if (hasNext()) {
            // Out of budget part way down the list rather than out of list. Nothing is decided.
            return Outcome.KEEP_SWEEPING;
        }
        if (postponed >= 0 && (accepted < 0 || postponed < accepted)) {
            if (passes < MAX_POSTPONE_PASSES) {
                passes++;
                cursor = postponed;
                postponed = -1;
                return Outcome.KEEP_SWEEPING;
            }
            // Budget spent. Drop the marker as well as the rewind: leaving it set keeps
            // isComplete() reporting a sweep in progress that nothing will ever advance, and a
            // caller waiting on that waits for the rest of the task.
            postponed = -1;
        }
        return accepted >= 0 ? Outcome.COMMIT : Outcome.NOTHING;
    }

    /**
     * Whether the sweep has finished looking, so that an empty result means there is nothing to find
     * rather than that the search has not got there yet. Callers that cannot tell these apart end
     * their search on its first tick, every tick.
     */
    public boolean isComplete() {
        return cursor >= size && postponed < 0;
    }
}
