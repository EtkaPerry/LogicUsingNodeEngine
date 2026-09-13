package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.util.Durations;
import com.etka.lune.util.Lang;

import java.util.List;

/**
 * Waits out a stretch of real time written the way a person would write it, then emits Success.
 *
 * <h2>Why this is not the Timer card with a bigger number</h2>
 *
 * <p>{@link TimerTask} counts game ticks, which is the right measure for the short waits it exists
 * for: a pause between two placements should be a pause in the game's own time. Stretch that to
 * five hours and it stops being right. Twenty ticks is a second only while the client is keeping
 * up, and a machine running Lune unattended for an afternoon does not - a stint at fifteen ticks a
 * second turns "five hours" into six and a half, and nothing on screen says so.</p>
 *
 * <p>So a Countdown measures elapsed milliseconds instead, and its units go up to days because
 * that is the length of wait it is for.</p>
 *
 * <h2>A paused game is not elapsed time</h2>
 *
 * <p>Comparing two readings of the clock would make a countdown expire over lunch, with the game
 * paused and the bot doing nothing. So the wait is accumulated per tick and a single tick may
 * contribute at most {@link #MAX_STEP_MILLIS}. Ordinary slowness counts as the real time it was;
 * the hour the game spent on the pause menu arrives as one gap and contributes a second.</p>
 *
 * <p>Nothing else starves this of ticks. {@code TaskRunner} runs a While monitor <em>alongside</em>
 * the step it guards rather than instead of it, and ticks every parallel circuit on every path, so
 * a Countdown keeps counting through a fight it is not involved in.</p>
 */
public final class CountdownTask implements Task {

    /**
     * How long a countdown is written in.
     *
     * <p>The labels are identifiers stored in saved tasks, so they stay English and only their
     * rendering is translated.</p>
     */
    public enum Unit {
        SECONDS("Seconds", 1L),
        MINUTES("Minutes", 60L),
        HOURS("Hours", 3_600L),
        DAYS("Days", 86_400L);

        private final String label;
        private final long seconds;

        Unit(String label, long seconds) {
            this.label = label;
            this.seconds = seconds;
        }

        public String label() {
            return label;
        }

        /** How many seconds one of this unit is. */
        public long seconds() {
            return seconds;
        }

        /** Names for a picker, shortest first. */
        public static List<String> labels() {
            return List.of(SECONDS.label, MINUTES.label, HOURS.label, DAYS.label);
        }

        /** Reads back a name from {@link #labels()}; falls back to minutes for junk. */
        public static Unit fromLabel(String label) {
            for (Unit unit : values()) {
                if (unit.label.equalsIgnoreCase(label)) {
                    return unit;
                }
            }
            return MINUTES;
        }
    }

    /**
     * The most one tick may add to the total.
     *
     * <p>Generous enough that an ordinary stutter is counted as the real time it was, small enough
     * that a paused or minimised game cannot dump hours in at once.</p>
     */
    private static final long MAX_STEP_MILLIS = 1_000L;

    /** Above this, the progress bar counts minutes rather than seconds. */
    private static final long MINUTE_SCALE_SECONDS = 3_600L;

    private final int amount;
    private final Unit unit;
    private final long totalMillis;
    private final StatusText status = new StatusText();

    private long remainingMillis;
    private long lastTickNanos;

    public CountdownTask(int amount, Unit unit) {
        this.amount = Math.max(0, amount);
        this.unit = unit == null ? Unit.MINUTES : unit;
        this.totalMillis = this.amount * this.unit.seconds() * 1_000L;
    }

    /** A wait is a wait; there is nothing here for the learner to get better at. */
    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    @Override
    public String name() {
        return Lang.get("lune.command.countdown.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Countdown");
    }

    @Override
    public void onStart(BotContext ctx) {
        remainingMillis = totalMillis;
        lastTickNanos = System.nanoTime();
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        long now = System.nanoTime();
        remainingMillis -= Math.clamp((now - lastTickNanos) / 1_000_000L, 0L, MAX_STEP_MILLIS);
        lastTickNanos = now;
        if (remainingMillis <= 0L) {
            remainingMillis = 0L;
            status.set("lune.status.countdown.finished");
            return TaskStatus.SUCCESS;
        }
        status.set("lune.status.countdown.remaining", describeRemaining());
        return TaskStatus.RUNNING;
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public TaskProgress progress() {
        long totalSeconds = Math.max(1L, totalMillis / 1_000L);
        long doneSeconds = Math.clamp((totalMillis - remainingMillis) / 1_000L, 0L, totalSeconds);
        if (totalSeconds <= MINUTE_SCALE_SECONDS) {
            return new TaskProgress((int) doneSeconds, (int) totalSeconds,
                    Lang.get("lune.unit.seconds"));
        }
        return new TaskProgress((int) (doneSeconds / 60L), (int) (totalSeconds / 60L),
                Lang.get("lune.unit.minutes"));
    }

    /** What is left, rounded up so a countdown never reads zero while it is still waiting. */
    private String describeRemaining() {
        return Durations.describe(Math.ceilDiv(Math.max(0L, remainingMillis), 1_000L));
    }

    /** The whole wait in seconds, for the card's own description of itself. */
    public long totalSeconds() {
        return amount * unit.seconds();
    }
}
