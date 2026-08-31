package com.etka.lune.bot.learning;

/**
 * Normalised result of one learned skill episode.
 *
 * <p>The learner compares work per tick instead of raw duration. That keeps a large piece of work
 * from looking worse merely because it contained more useful work than a small one. Completion is
 * still part of the score, so abandoning half a tree quickly is not mistaken for a good chop.</p>
 */
public record SkillOutcome(double reward, int workUnits, int expectedUnits, long elapsedTicks,
                           double unitsPerSecond, double completion, boolean completed,
                           double usualTicksPerUnit, double previousBestTicksPerUnit,
                           boolean bestTime, boolean scored) {

    private static final double BASE_COMPLETION_REWARD = 2.0;
    private static final double COMPLETION_WEIGHT = 8.0;
    private static final double MAX_SPEED_ADJUSTMENT = 4.0;

    public static SkillOutcome evaluate(boolean completed, int workUnits, int expectedUnits,
                                        long elapsedTicks, double usualTicksPerUnit) {
        return evaluate(completed, workUnits, expectedUnits, elapsedTicks, usualTicksPerUnit, 0.0);
    }

    public static SkillOutcome evaluate(boolean completed, int workUnits, int expectedUnits,
                                        long elapsedTicks, double usualTicksPerUnit,
                                        double previousBestTicksPerUnit) {
        int work = Math.max(0, workUnits);
        int expected = Math.max(1, Math.max(work, expectedUnits));
        long ticks = Math.max(1L, elapsedTicks);
        double completion = Math.min(1.0, (double) work / expected);
        double unitsPerSecond = work == 0 ? 0.0 : work * 20.0 / ticks;
        double ticksPerUnit = work == 0 ? Double.POSITIVE_INFINITY : (double) ticks / work;
        boolean bestTime = completed && work > 0
                && (previousBestTicksPerUnit <= 0.0
                    || ticksPerUnit + 0.0001 < previousBestTicksPerUnit);

        double speedAdjustment = 0.0;
        if (work > 0 && usualTicksPerUnit > 0.0) {
            double relativeImprovement = usualTicksPerUnit / ticksPerUnit - 1.0;
            speedAdjustment = clamp(relativeImprovement * 6.0,
                    -MAX_SPEED_ADJUSTMENT, MAX_SPEED_ADJUSTMENT);
        }

        double reward;
        if (completed && work == 0) {
            // Finishing with nothing to show for it is usually the world's answer, not the
            // tactic's: an empty pickup radius, a seam that was already clear, a sweep that
            // correctly reported no visible target left. Scoring that as total failure put every
            // mining tactic at -10 whenever a run started somewhere barren, which taught the
            // profile that all three were equally hopeless. It proved nothing, so it scores
            // nothing. A job that gave up without finishing still keeps the failure below.
            reward = 0.0;
        } else if (work == 0) {
            reward = -10.0;
        } else if (completed) {
            reward = BASE_COMPLETION_REWARD + COMPLETION_WEIGHT * completion + speedAdjustment;
        } else {
            // Partial work can make a failure less bad, but never turn an unfinished tactic into
            // a positive example merely because it abandoned the job quickly.
            reward = Math.min(-0.5,
                    -6.0 + COMPLETION_WEIGHT * completion + speedAdjustment);
        }
        // Establishing a baseline is neutral; beating a real prior best deserves extra credit.
        if (bestTime && previousBestTicksPerUnit > 0.0) {
            reward += 2.0;
        }
        return new SkillOutcome(clamp(reward, -10.0, 14.0), work, expected, ticks,
                unitsPerSecond, completion, completed, Math.max(0.0, usualTicksPerUnit),
                Math.max(0.0, previousBestTicksPerUnit), bestTime, true);
    }

    /**
     * An episode too short to be a duration. It keeps its telemetry so the journal can still show
     * what happened, but it scores nothing and claims no best time.
     *
     * <p>The store already refuses to learn from these. Handing the caller a fully scored outcome
     * anyway made the refusal cosmetic: a job that opened and closed inside one tick still reads as
     * "1/1 units, completed", which is the top of the scale, so every one of them paid the caller
     * the full completion reward. One recorded run spent its whole length re-opening a movement
     * episode that was already standing on its goal - 3,701 of its 3,714 movement episodes closed
     * in a single tick - and banked +10.00 per tick for 921 seconds without mining a log. Nothing
     * was measured, so nothing is owed.</p>
     */
    public static SkillOutcome unmeasured(int workUnits, int expectedUnits, long elapsedTicks) {
        int work = Math.max(0, workUnits);
        int expected = Math.max(1, Math.max(work, expectedUnits));
        long ticks = Math.max(0L, elapsedTicks);
        return new SkillOutcome(0.0, work, expected, ticks, 0.0,
                Math.min(1.0, (double) work / expected), false, 0.0, 0.0, false, false);
    }

    public String summary() {
        return workUnits + "/" + expectedUnits + " units in " + elapsedTicks + " ticks ("
                + String.format(java.util.Locale.ROOT, "%.2f", unitsPerSecond)
                + "/s, reward "
                + String.format(java.util.Locale.ROOT, "%.2f", reward)
                + (bestTime ? ", best time" : "")
                + (scored ? "" : ", too short to score") + ")";
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
