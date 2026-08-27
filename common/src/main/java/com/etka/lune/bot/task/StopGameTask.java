package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;

/**
 * Ends the session: saves and closes the world, and optionally the game with it.
 *
 * <p>The point is an unattended run that finishes cleanly rather than one that leaves a client
 * sitting on a world all night. Putting it in a routine means "gather this, then stop" is a thing
 * the user can express without watching for it.
 *
 * <p>Works on a server too, where leaving is the only one of the two endings that means anything -
 * a server world cannot be paused. Closing the client afterwards stays the user's choice either way.
 */
public final class StopGameTask implements Task {

    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    /** Ticks to let the level save before the client is asked to close. */
    private static final int SAVE_GRACE_TICKS = 20;

    private final boolean closeClient;

    private int ticks;
    private boolean leaving;
    private String status = "";

    public StopGameTask(boolean closeClient) {
        this.closeClient = closeClient;
    }

    @Override
    public String name() {
        return "Stop the Game";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        ticks = 0;
        leaving = false;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (ctx.mc.level == null) {
            status = "already out of the world";
            return TaskStatus.SUCCESS;
        }
        if (!leaving) {
            leaving = true;
            boolean single = ctx.mc.hasSingleplayerServer();
            status = closeClient
                    ? (single ? "saving the world and closing the game" : "disconnecting and closing the game")
                    : (single ? "saving and leaving the world" : "disconnecting from the server");
            ctx.debug.decide("routine asked to stop; "
                    + (single ? "saving the world first" : "disconnecting"));
            // Scheduled rather than called straight from the tick: this tears down the very level
            // whose tick loop is running, and doing that mid-tick is how a client crashes on the
            // way out instead of saving.
            ctx.mc.schedule(() -> {
                // Re-checked inside the scheduled block: between here and there the player can be
                // kicked, or the level torn down for some other reason, and dereferencing a level
                // that has already gone is a crash on the way out rather than a clean exit.
                if (ctx.mc.level != null) {
                    ctx.mc.level.disconnect(null);
                }
            });
            return TaskStatus.RUNNING;
        }
        // Give the save a moment before pulling the client out from under it.
        if (closeClient && ++ticks >= SAVE_GRACE_TICKS) {
            ctx.mc.schedule(ctx.mc::stop);
            status = "closing the game";
            return TaskStatus.SUCCESS;
        }
        if (!closeClient && ctx.mc.level == null) {
            status = "left the world";
            return TaskStatus.SUCCESS;
        }
        status = closeClient ? "closing the game" : "leaving the world";
        return TaskStatus.RUNNING;
    }
}
