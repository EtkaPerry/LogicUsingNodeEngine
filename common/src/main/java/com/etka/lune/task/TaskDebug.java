package com.etka.lune.task;

/**
 * Breakpoints, and the one-card-at-a-time run they make possible.
 *
 * <p>A task that misbehaves is hard to read at twenty ticks a second: the canvas lights a card, the
 * bot does something unexpected, and by the time the player has looked up the signal is three cards
 * further on. A breakpoint stops the task <em>on arrival at a card, before it is built</em>, which
 * is the only moment where the graph and the world agree about where the run has got to.</p>
 *
 * <h2>This is a pause, not a behaviour</h2>
 *
 * <p>Holding at a card takes nothing away from a card and gives nothing to the engine: no card
 * gains a reflex and no reflex gains a card. It is the player pressing pause with their finger on
 * one particular step, the same act {@code BotEngine.setPaused} already offers with no particular
 * step in mind. The held tick drives nothing - {@code TaskRunner} clears the movement input rather
 * than leaving a walk key down - so a bot stopped at a breakpoint stands still instead of drifting
 * into the hole it was about to be stopped from walking into.</p>
 *
 * <h2>Static, because there is one run</h2>
 *
 * <p>The engine runs one task at a time, and the canvas the player is looking at is the same
 * {@link TaskGraph} object that task is walking. So this is state about "the run", not about a
 * particular runner, and a second {@code TaskRunner} built while one is paused would be a bug
 * elsewhere rather than a case to model here.</p>
 */
public final class TaskDebug {

    /** Whether breakpoints are honoured at all. Off leaves the red dots in place but ignored. */
    private static boolean armed = true;
    /** A run is sitting on a card, waiting for Step or Continue. */
    private static boolean holding;
    /** The next card the run reaches holds, whether or not it carries a breakpoint. */
    private static boolean stepping;
    /** Which card the run is sitting on. An id, never a rendered name. */
    private static String haltedNodeId;
    /** Cards left behind since the last release, for the "stepped 4 cards" reading on the canvas. */
    private static int stepsTaken;

    private TaskDebug() {}

    /**
     * Called once as control reaches a card, before anything is built or ticked.
     *
     * <p>Once per arrival is what makes the release work: the card the run was released onto is
     * not asked again, so Step lands on the next card rather than immediately on the same one.</p>
     *
     * @return true when the run must hold here
     */
    public static boolean arrive(TaskNode node) {
        if (node == null || node.id == null) {
            return false;
        }
        if (!stepping && !(armed && node.breakpoint)) {
            return false;
        }
        holding = true;
        stepping = false;
        haltedNodeId = node.id;
        return true;
    }

    public static boolean holding() {
        return holding;
    }

    public static String haltedNodeId() {
        return haltedNodeId;
    }

    public static int stepsTaken() {
        return stepsTaken;
    }

    public static boolean isHalted(TaskNode node) {
        return holding && node != null && node.id != null && node.id.equals(haltedNodeId);
    }

    /** Runs the held card and holds again on the next one. */
    public static void step() {
        if (!holding) {
            // Pressing Step on a running task is the ordinary way to ask it to stop somewhere
            // sensible, so it arms a hold rather than doing nothing until a breakpoint turns up.
            stepping = true;
            return;
        }
        holding = false;
        stepping = true;
        stepsTaken++;
    }

    /** Runs on until the next breakpoint. */
    public static void resume() {
        holding = false;
        stepping = false;
        haltedNodeId = null;
        stepsTaken = 0;
    }

    /** Stops the run at whichever card it reaches next, with no breakpoint needed. */
    public static void breakAtNextCard() {
        stepping = true;
    }

    /** True while a hold has been asked for but the run has not reached a card yet. */
    public static boolean waitingToBreak() {
        return stepping && !holding;
    }

    /** The run ended. Breakpoints stay where the player put them; the hold does not. */
    public static void clear() {
        holding = false;
        stepping = false;
        haltedNodeId = null;
        stepsTaken = 0;
    }

    public static boolean armed() {
        return armed;
    }

    /**
     * Switches every breakpoint on or off at once.
     *
     * <p>Disarming also releases a hold in progress, because the alternative is a task frozen on a
     * card by a breakpoint the player has just switched off, with nothing on screen still claiming
     * responsibility for it.</p>
     */
    public static void setArmed(boolean value) {
        armed = value;
        if (!armed && holding) {
            resume();
        }
    }

    public static boolean toggle(TaskNode node) {
        if (node == null) {
            return false;
        }
        node.breakpoint = !node.breakpoint;
        return node.breakpoint;
    }

    public static int count(TaskGraph task) {
        if (task == null || task.nodes == null) {
            return 0;
        }
        int total = 0;
        for (TaskNode node : task.nodes) {
            if (node != null && node.breakpoint) {
                total++;
            }
        }
        return total;
    }

    /** Clears every breakpoint in a task, and says how many there were. */
    public static int clearAll(TaskGraph task) {
        int cleared = count(task);
        if (task != null && task.nodes != null) {
            for (TaskNode node : task.nodes) {
                if (node != null) {
                    node.breakpoint = false;
                }
            }
        }
        return cleared;
    }
}
