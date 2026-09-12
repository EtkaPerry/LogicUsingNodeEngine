package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.WhileMonitor;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.Leash;
import com.etka.lune.bot.util.LeashPolicy;
import com.etka.lune.waypoint.Waypoint;
import com.etka.lune.waypoint.WaypointStore;
import net.minecraft.core.BlockPos;

import java.util.Optional;

/**
 * Keeps the bot inside an area: a While circuit that stops its recovery the moment it is home
 * again.
 *
 * <p>It is a circuit rather than a setting because that is what it actually is - a rule that
 * watches the area and drives back when needed, the same shape as Self Preservation. Wire it to a
 * node's While pin to run it beside that step, or drop it in an Always circuit to leash the whole
 * task, and nothing else in the graph has to know it exists.
 *
 * <p>Two halves make it work. This circuit is the recovery: if the bot is outside the circle, it
 * walks back in and only then returns control. The other half is {@link Leash}, which it publishes
 * the area into so that every search in the run stops offering work on the far side of the boundary
 * in the first place - a monitor on its own is a yo-yo, because the walk out has already been spent
 * by the time it notices.
 *
 * <p>The anchor is taken once per run, never per node. Re-measuring from wherever each step began
 * is how a leash quietly stops being one: twenty nodes, sixty blocks of slack each, and the bot is
 * a mile away with every individual step having behaved perfectly.
 */
public final class StayNearTask implements WhileMonitor {

    /** Anchor mode: measure from where the run started. */
    public static final String FROM_RUN_START = "Where the run started";
    /** Anchor mode: measure from a saved waypoint. */
    public static final String FROM_WAYPOINT = "Waypoint";

    private final String anchorMode;
    private final String waypointName;
    private final int radius;

    private BlockPos anchor;
    private Task recovery;
    private final StatusText status = new StatusText().set("lune.status.stay_near.watching");

    public StayNearTask(String anchorMode, String waypointName, int radius) {
        this.anchorMode = anchorMode == null ? FROM_RUN_START : anchorMode;
        this.waypointName = waypointName == null ? "" : waypointName;
        this.radius = Math.max(1, radius);
    }

    @Override
    public String name() {
        return Lang.get("lune.task.stay_near.name");
    }

    /** The English this used to be, so the learner's rows survive being translated. */
    @Override
    public String learningId() {
        return Task.learningName("Stay Near");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        anchor = resolveAnchor(ctx);
        if (anchor == null) {
            if (anchorMode.equals(FROM_WAYPOINT)) {
                status.set("lune.status.stay_near.no_waypoint_named_dimension", waypointName);
            } else {
                status.set("lune.status.stay_near.no_run_anchor_yet");
            }
            Leash.get().release();
            return;
        }
        Leash.get().hold(anchor, radius);
        status.set("lune.status.stay_near.holding_blocks", radius, anchor.toShortString());
    }

    @Override
    public boolean shouldTakeControl(BotContext ctx) {
        if (anchor == null) {
            // A waypoint can be created, renamed or left behind in another dimension after the run
            // starts, so keep asking rather than deciding once that there is nothing to hold.
            anchor = resolveAnchor(ctx);
            if (anchor == null) {
                return false;
            }
            Leash.get().hold(anchor, radius);
        }
        double out = Leash.get().distanceFrom(ctx.player.getX(), ctx.player.getZ());
        if (!LeashPolicy.outside(out, radius)) {
            stopRecovery(ctx);
            status.set("lune.status.stay_near.inside_area_blocks_out", out, radius);
            return false;
        }
        return true;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (anchor == null) {
            return TaskStatus.SUCCESS;
        }
        double out = Leash.get().distanceFrom(ctx.player.getX(), ctx.player.getZ());
        if (out <= returnRadius()) {
            stopRecovery(ctx);
            status.set("lune.status.stay_near.back_inside_area");
            return TaskStatus.SUCCESS;
        }
        if (recovery == null) {
            recovery = new GotoTask(new Goals.Near(anchor, returnRadius()), true, false);
            recovery.start(ctx);
        }
        TaskStatus result = recovery.tick(ctx);
        status.set("lune.status.stay_near.blocks_outside_area_heading_back", out - radius);
        if (result == TaskStatus.RUNNING) {
            return TaskStatus.RUNNING;
        }
        stopRecovery(ctx);
        if (result == TaskStatus.FAILED) {
            // No route home. Saying so beats pretending: the run continues, out of bounds, and the
            // status and journal carry the reason rather than the bot silently giving up on the
            // leash or standing still until something else moves it.
            ctx.debug.recordFailure(name(), "no route back to " + anchor.toShortString());
            status.set("lune.status.stay_near.cannot_get_back_area");
            return TaskStatus.FAILED;
        }
        return TaskStatus.RUNNING;
    }

    /** How far in the bot has to come before work resumes. */
    private int returnRadius() {
        return LeashPolicy.returnRadius(radius);
    }

    private BlockPos resolveAnchor(BotContext ctx) {
        if (FROM_WAYPOINT.equals(anchorMode)) {
            Optional<Waypoint> waypoint = WaypointStore.get().byName(waypointName);
            if (waypoint.isEmpty()) {
                return null;
            }
            // A waypoint in the overworld says nothing about where to stand in the Nether, and
            // leashing to its coordinates there would hold the bot to an unrelated spot.
            String dimension = WaypointStore.currentDimension();
            return waypoint.get().dimension().equals(dimension) ? waypoint.get().pos() : null;
        }
        return ctx.runAnchor;
    }

    @Override
    public void onControlReleased(BotContext ctx) {
        stopRecovery(ctx);
    }

    @Override
    public void onPause(BotContext ctx) {
        if (recovery != null) {
            recovery.onPause(ctx);
        }
        ctx.input.reset();
    }

    @Override
    public void onStop(BotContext ctx) {
        stopRecovery(ctx);
        Leash.get().release();
        status.set("lune.status.stay_near.watching");
    }

    private void stopRecovery(BotContext ctx) {
        if (recovery != null) {
            recovery.stop(ctx);
            recovery = null;
        }
    }
}
