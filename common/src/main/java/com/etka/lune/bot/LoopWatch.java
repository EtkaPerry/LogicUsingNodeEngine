package com.etka.lune.bot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notices when one step of a task is what is costing the player their frame rate, and says so.
 *
 * <h2>Why this exists instead of a stricter rule</h2>
 *
 * <p>The obvious fix for a job that spins - searching a barren area, finishing, being restarted by
 * its own {@code x∞} card, searching again - is to make the job report failure so the circuit
 * stops. That is the wrong trade. A step that fails takes down the circuit that fed it whenever no
 * Fail edge is wired, so a card at the end of a chain would be able to cancel perfectly good work
 * in front of it because of a wiring mistake. Wiring mistakes are normal; losing the rest of the
 * task over one is not.</p>
 *
 * <p>So nothing is cancelled. The condition is measured, and the player is told what is happening
 * and why, in the terms that make it actionable: which step, how much of each tick it is taking,
 * and whether it is set to repeat forever. They decide whether that was the intent.</p>
 *
 * <h2>What counts</h2>
 *
 * <p>Two independent signs, because the same symptom has two shapes. A step may hold the client
 * inside a single long tick, or it may be cheap individually and restart many times a second. Both
 * are only interesting when the step is <em>also</em> achieving nothing: a mining run that is
 * genuinely producing blocks is allowed to be expensive, and interrupting the player to say so
 * would be noise.</p>
 *
 * <p>All access is from the client thread, which is why nothing here is synchronised.</p>
 */
public final class LoopWatch {

    /**
     * Microseconds of one tick a single step may average before it is the reason frames stopped.
     *
     * <p>A client tick is 50 ms. At 15 ms one step is taking most of the budget that is actually
     * free once vanilla has had its share, which is where chunk loading visibly falls behind - the
     * measured case was 52 ms per tick, holding the client at 9 TPS.</p>
     */
    private static final long HEAVY_MICROS_PER_TICK = 15_000;
    /** Restarts per second past which a step is thrashing rather than working. */
    private static final double SPINNING_RESTARTS_PER_SECOND = 4.0;
    /** Judged over this long, so one bad tick is not mistaken for a job that never lets go. */
    static final long WINDOW_NANOS = 5_000_000_000L;
    /** Too few samples to judge; a window that just opened says nothing. */
    static final int MIN_TICKS = 40;
    /** Guards the map against a pathological graph; far more nodes than any real task has. */
    private static final int MAX_TRACKED = 64;

    /** One step worth interrupting the player about. */
    public record Spin(String nodeId, String label, String taskName, long microsPerTick,
                       double restartsPerSecond, boolean permanent) {

        /** True when the cost is the long-tick shape rather than the restart-thrash shape. */
        public boolean heavyPerTick() {
            return microsPerTick >= HEAVY_MICROS_PER_TICK;
        }
    }

    private static final LoopWatch INSTANCE = new LoopWatch();

    private final Map<String, Window> windows = new LinkedHashMap<>();
    private long windowBegan = System.nanoTime();
    private Spin worst;

    private LoopWatch() {}

    public static LoopWatch get() {
        return INSTANCE;
    }

    private static final class Window {
        private String label = "";
        private String taskName = "";
        private boolean permanent;
        private int ticks;
        private long micros;
        private int restarts;
        private boolean progressed;
    }

    /**
     * Charges one tick of a step's own runtime to it.
     *
     * @param progressed whether the step reported achieving something this tick; a productive step
     *                   is never reported however expensive it is
     */
    public void sample(String nodeId, String label, String taskName, boolean permanent,
                       long nanos, boolean progressed) {
        if (nodeId == null) {
            return;
        }
        Window window = windowFor(nodeId);
        if (window == null) {
            return;
        }
        window.label = label == null ? "" : label;
        window.taskName = taskName == null ? "" : taskName;
        window.permanent = permanent;
        window.ticks++;
        window.micros += Math.max(0L, nanos) / 1000L;
        window.progressed |= progressed;
    }

    /** Records a step being built and started. Called for every start, so the rate is meaningful. */
    public void recordRestart(String nodeId) {
        if (nodeId == null) {
            return;
        }
        Window window = windowFor(nodeId);
        if (window != null) {
            window.restarts++;
        }
    }

    private Window windowFor(String nodeId) {
        roll();
        Window window = windows.get(nodeId);
        if (window == null) {
            if (windows.size() >= MAX_TRACKED) {
                return null;
            }
            window = new Window();
            windows.put(nodeId, window);
        }
        return window;
    }

    /** Forgets everything. Called when a run starts or stops, so verdicts never cross runs. */
    public void clear() {
        windows.clear();
        windowBegan = System.nanoTime();
        worst = null;
    }

    /**
     * The step currently worth interrupting the player about, or {@code null}.
     *
     * <p>Held from the last completed window rather than recomputed, so the answer is stable for
     * the several seconds a prompt needs to stay on screen saying the same thing.</p>
     */
    public Spin worst() {
        roll();
        return worst;
    }

    private void roll() {
        long now = System.nanoTime();
        if (now - windowBegan < WINDOW_NANOS) {
            return;
        }
        double seconds = (now - windowBegan) / 1_000_000_000.0;
        worst = null;
        long worstCost = 0;
        for (Map.Entry<String, Window> entry : windows.entrySet()) {
            Window window = entry.getValue();
            long perTick = window.ticks == 0 ? 0 : window.micros / window.ticks;
            double restartRate = seconds <= 0 ? 0 : window.restarts / seconds;
            if (!isWorthReporting(window.ticks, perTick, restartRate, window.progressed)) {
                continue;
            }
            // Ranked by what it actually costs, so the loudest problem is the one named.
            long cost = Math.max(perTick, (long) (restartRate * 1000));
            if (cost > worstCost) {
                worstCost = cost;
                worst = new Spin(entry.getKey(), window.label, window.taskName, perTick,
                        restartRate, window.permanent);
            }
        }
        windows.clear();
        windowBegan = now;
    }

    /**
     * The rule itself, kept pure so it can be tested without a client.
     *
     * <p>A step that achieved something is never reported: being slow while producing is a job
     * doing its work, and the player did ask for it.</p>
     */
    static boolean isWorthReporting(int ticks, long microsPerTick, double restartsPerSecond,
                                    boolean progressed) {
        if (progressed || ticks < MIN_TICKS) {
            return false;
        }
        return microsPerTick >= HEAVY_MICROS_PER_TICK
                || restartsPerSecond >= SPINNING_RESTARTS_PER_SECOND;
    }
}
