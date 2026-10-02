package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.path.Goal;
import com.etka.lune.bot.path.MovementHelper;
import com.etka.lune.util.Lang;

/**
 * A walk to somewhere far off that digs its way out of trouble rather than stopping in it.
 *
 * <p>Travel does not break blocks. A route across the country walks round a hill, and a bot
 * tunnelling through it looks nothing like a person on their way somewhere. But a walk of a
 * thousand blocks sooner or later drops into something the walking search cannot climb out of: a
 * pond at the bottom of a pit, a ravine ledge. The first measured Find Stronghold run that got past
 * the throw ended exactly there, eleven blocks down and six seconds in, with two thousand blocks to
 * go - "no dry or swimming route from here", three times, and the card failed.</p>
 *
 * <p>So the walk starts as ordinary travel, and when a stretch of it fails the next is tried the
 * other way - digging allowed, then not - for as long as the failures keep happening somewhere
 * new. A retry has to change something: this one changes the mode, and the place. What ends it is
 * {@link #MAX_FAILURES_HERE} failures in a row that got less than {@link #PROGRESS} blocks
 * further, which is the walk genuinely having nowhere to go. A mangrove swamp was the case that
 * showed one retry was not enough: the walk stalled in its roots, and digging was refused a moment
 * later for the water round them, in the same spot.</p>
 */
public final class FarWalkTask implements Task {

    /** Blocks closer to the goal that make a failure a new failure rather than the same one. */
    private static final double PROGRESS = 8.0;
    private static final int MAX_FAILURES_HERE = 3;
    /** However much ground each stretch gains, a walk that fails this often is not working. */
    private static final int MAX_FAILURES = 24;

    private final Goal goal;
    private final boolean sprint;
    private GotoTask walk;
    private boolean digging;
    private int failures;
    private int failuresHere;
    private double closestAtFailure;

    public FarWalkTask(Goal goal, boolean sprint) {
        this.goal = goal;
        this.sprint = sprint;
    }

    @Override
    public String name() {
        return Lang.get("lune.task.goto.name", goal.describe());
    }

    /** English on purpose: this is the learner's row key, and is never shown. */
    @Override
    public String learningId() {
        return Task.learningName("Go to " + goal.describe());
    }

    /** The walks inside learn as movement; the wrapper around them is not a skill of its own. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    /** Why the last stretch failed, kept for the status until the new one has something to say. */
    private final StatusText lastFailure = new StatusText();

    @Override
    public StatusText statusLine() {
        if (walk == null) {
            return StatusText.EMPTY;
        }
        StatusText now = walk.statusLine();
        return now.isBlank() ? lastFailure : now;
    }

    /** Whether the stretch being walked now may dig. */
    public boolean digging() {
        return digging;
    }

    @Override
    public void onStart(BotContext ctx) {
        digging = false;
        failures = 0;
        failuresHere = 0;
        closestAtFailure = Double.POSITIVE_INFINITY;
        walk = new GotoTask(goal, sprint, false);
        walk.start(ctx);
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        TaskStatus result = walk.tick(ctx);
        if (result != TaskStatus.FAILED) {
            return result;
        }
        walk.stop(ctx);
        double left = goal.heuristic(MovementHelper.feetPosition(ctx.player));
        if (left < closestAtFailure - PROGRESS) {
            failuresHere = 0;
        }
        closestAtFailure = Math.min(closestAtFailure, left);
        failuresHere++;
        failures++;
        if (failuresHere >= MAX_FAILURES_HERE || failures >= MAX_FAILURES) {
            // The last stretch's own reason is the status left behind for the caller.
            return TaskStatus.FAILED;
        }
        digging = !digging;
        ctx.debug.decide(digging ? "walking route failed; trying again with digging allowed"
                : "digging route failed; trying the walk again from here");
        StatusText why = walk.statusLine();
        walk = new GotoTask(goal, sprint, digging);
        walk.start(ctx);
        lastFailure.set(why);
        return TaskStatus.RUNNING;
    }

    @Override
    public void onPause(BotContext ctx) {
        if (walk != null) {
            walk.onPause(ctx);
        }
        Task.super.onPause(ctx);
    }

    @Override
    public void onStop(BotContext ctx) {
        if (walk != null) {
            walk.stop(ctx);
            walk = null;
        }
        ctx.input.reset();
    }
}
