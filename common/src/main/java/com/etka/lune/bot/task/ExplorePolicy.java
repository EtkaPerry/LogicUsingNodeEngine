package com.etka.lune.bot.task;

import java.util.List;

/**
 * Pure heading rules for exploring; kept testable without Minecraft.
 *
 * <p>The failure this exists to stop is the one that looks worst from outside: a bot that walks
 * north, gives up, walks south over the ground it just covered, gives up again, and spends a minute
 * turning around on the spot. Each individual decision is defensible - the route really was blocked
 * - but nothing was stopping the next choice from undoing the last one.</p>
 *
 * <p>So a new heading is pushed away from the reverse of the headings just abandoned. Only the last
 * couple are remembered: guarding against everything tried all run would eventually exclude every
 * direction and leave the bot with nowhere to go.</p>
 */
final class ExplorePolicy {

    static final String SHORT_COMMIT = "short-heading-commit";
    static final String BALANCED_COMMIT = "balanced-heading-commit";
    static final String LONG_COMMIT = "long-heading-commit";
    static final List<String> ACTIONS = List.of(SHORT_COMMIT, BALANCED_COMMIT, LONG_COMMIT);
    static final String DEFAULT = BALANCED_COMMIT;

    /** A heading this close to the reverse of a recent one walks back over ground already seen. */
    static final float REVERSAL_GUARD_DEGREES = 60.0F;
    /** A heading this close to one that was just blocked runs into the same obstacle. */
    static final float BLOCKED_GUARD_DEGREES = 60.0F;
    /** How far to rotate when a candidate is rejected. */
    static final float TURN_STEP_DEGREES = 90.0F;
    /** Rotations to try before accepting whatever is left; three covers the whole compass. */
    static final int MAX_ADJUSTMENTS = 3;

    private ExplorePolicy() {}

    /** Every variant still commits for several stops; only the useful search horizon changes. */
    static int commitSteps(String action) {
        return switch (action) {
            case SHORT_COMMIT -> 2;
            case LONG_COMMIT -> 5;
            default -> 3;
        };
    }

    /**
     * Nudges a candidate heading away from doubling back.
     *
     * @param candidate the heading the chooser wants
     * @param recent    the most recently abandoned headings, newest first; nulls are ignored
     * @return a heading at least {@link #REVERSAL_GUARD_DEGREES} from the reverse of each recent
     *         one where possible, and the best available rotation otherwise
     */
    static float avoidDoublingBack(float candidate, Float... recent) {
        float adjusted = wrap(candidate);
        for (int attempt = 0; attempt <= MAX_ADJUSTMENTS; attempt++) {
            if (!doublesBack(adjusted, recent)) {
                return adjusted;
            }
            adjusted = wrap(adjusted + TURN_STEP_DEGREES);
        }
        return adjusted;
    }

    /**
     * Nudges a candidate away from headings whose routes have just failed.
     *
     * <p>The reversal guard alone permits a two-heading loop, and that loop is what a coastline
     * produces. West is water so the route fails; south is not the reverse of west, so it is
     * allowed, and it is also water; west is not the reverse of south, so west comes round again.
     * The journal shows exactly that alternation running for thousands of ticks. Blocked headings
     * therefore have to be remembered as blocked, not merely as walked.
     *
     * @param candidate the heading the chooser wants
     * @param blocked   headings whose routes failed recently; nulls are ignored
     */
    static float avoidBlocked(float candidate, Float... blocked) {
        float adjusted = wrap(candidate);
        for (int attempt = 0; attempt <= MAX_ADJUSTMENTS; attempt++) {
            if (!isBlocked(adjusted, blocked)) {
                return adjusted;
            }
            adjusted = wrap(adjusted + TURN_STEP_DEGREES);
        }
        return adjusted;
    }

    /** True when this heading is close enough to a recently blocked one to fail the same way. */
    static boolean isBlocked(float heading, Float... blocked) {
        for (Float previous : blocked) {
            if (previous == null) {
                continue;
            }
            if (Math.abs(wrap(heading - previous)) < BLOCKED_GUARD_DEGREES) {
                return true;
            }
        }
        return false;
    }

    /** True when this heading would send the bot back the way it just came. */
    static boolean doublesBack(float heading, Float... recent) {
        for (Float previous : recent) {
            if (previous == null) {
                continue;
            }
            float reverse = wrap(previous + 180.0F);
            if (Math.abs(wrap(heading - reverse)) < REVERSAL_GUARD_DEGREES) {
                return true;
            }
        }
        return false;
    }

    /** Degrees folded into (-180, 180]. */
    static float wrap(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped > 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped <= -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
