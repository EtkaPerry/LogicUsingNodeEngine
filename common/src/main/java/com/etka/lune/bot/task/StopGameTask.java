package com.etka.lune.bot.task;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Ends the session, at one of three depths.
 *
 * <p>The point is an unattended run that finishes cleanly rather than one that leaves a client
 * sitting on a world all night, and how far to back out is a real choice:</p>
 *
 * <ul>
 *   <li>{@link Ending#PAUSE} keeps everything exactly where it is. In singleplayer the world clock
 *       stops too, so nothing decays, no mob wanders in overnight, and the world is still there to
 *       look at in the morning.</li>
 *   <li>{@link Ending#MENU} saves and leaves, landing on the title screen.</li>
 *   <li>{@link Ending#QUIT} saves, leaves and closes the client.</li>
 * </ul>
 *
 * <p>Pausing does nothing on a server - the world keeps running and the player keeps standing in
 * it, which is the opposite of the intent - so that ending refuses there and says which of the
 * other two to use. Leaving and quitting work on a server, where leaving is the only one of the
 * three that means anything.</p>
 */
public final class StopGameTask implements Task {

    /** How far to back out of the session. */
    public enum Ending {
        PAUSE("Pause the game"),
        MENU("Return to main menu"),
        QUIT("Quit the game");

        private final String label;

        Ending(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Resolves a stored or chosen label, defaulting to the safest ending rather than failing. */
        public static Ending fromLabel(String label) {
            for (Ending ending : values()) {
                if (ending.label.equalsIgnoreCase(label)) {
                    return ending;
                }
            }
            return PAUSE;
        }
    }

    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    private final Ending ending;

    private boolean leaving;
    private final StatusText status = new StatusText();

    public StopGameTask(Ending ending) {
        this.ending = ending == null ? Ending.PAUSE : ending;
    }

    @Override
    public String name() {
        return Lang.get(ending == Ending.PAUSE
                ? "lune.task.stop_game.pause" : "lune.task.stop_game.stop");
    }

    @Override
    public StatusText statusLine() {
        return status;
    }

    @Override
    public void onStart(BotContext ctx) {
        leaving = false;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (ending == Ending.PAUSE) {
            return pause(ctx);
        }
        if (ctx.mc.level == null) {
            status.set(!leaving ? "lune.status.stop_game.already_out"
                    : ending == Ending.QUIT ? "lune.status.stop_game.closing_game"
                    : "lune.status.stop_game.left_world");
            return TaskStatus.SUCCESS;
        }
        if (!leaving) {
            leaving = true;
            boolean single = ctx.mc.hasSingleplayerServer();
            boolean quit = ending == Ending.QUIT;
            status.set(quit
                    ? (single ? "lune.status.stop_game.saving_and_closing"
                            : "lune.status.stop_game.disconnecting_and_closing")
                    : (single ? "lune.status.stop_game.saving_and_leaving"
                            : "lune.status.stop_game.disconnecting"));
            ctx.debug.decide("task asked to stop; "
                    + (single ? "saving the world first" : "disconnecting"));
            // Scheduled rather than called straight from the tick: this tears down the very level
            // whose tick loop is running, and doing that mid-tick is how a client crashes on the
            // way out instead of saving.
            //
            // The whole ending lives in this one block on purpose. Leaving the world makes
            // BotEngine drop every task, so a follow-up step in a later tick would never run and
            // "close the game too" would silently never happen.
            ctx.mc.schedule(() -> {
                // Re-checked inside the scheduled block: between here and there the player can be
                // kicked, or the level torn down for some other reason, and dereferencing a level
                // that has already gone is a crash on the way out rather than a clean exit.
                if (ctx.mc.level != null) {
                    // Vanilla's own Save and Quit. It shows the saving screen, blocks until the
                    // integrated server has finished writing, and lands on the title screen.
                    //
                    // The quit message is not decoration: a null reason reaches
                    // DisconnectedScreen, whose MultiLineTextWidget dereferences it while the
                    // layout is measured, so quitting this way with no message crashes the client
                    // instead of ending the session.
                    ctx.mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
                }
                if (quit) {
                    ctx.mc.stop();
                }
            });
            return TaskStatus.RUNNING;
        }
        if (ending == Ending.QUIT) {
            status.set("lune.status.stop_game.closing_game");
        } else {
            status.set("lune.status.stop_game.leaving_world");
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus pause(BotContext ctx) {
        if (!ctx.mc.hasSingleplayerServer()) {
            status.set("lune.status.stop_game.server_world_keeps_running_choose_return");
            return TaskStatus.FAILED;
        }
        if (ctx.mc.screen instanceof PauseScreen) {
            status.set("lune.status.stop_game.paused");
            return TaskStatus.SUCCESS;
        }
        // Scheduled rather than opened inline: this runs from the client tick, and swapping the
        // screen out from under the loop that is running is how a menu opens half-initialised.
        // `true` is the pause-the-world form; the cosmetic one does not stop the clock.
        ctx.mc.schedule(() -> ctx.mc.setScreen(new PauseScreen(true)));
        ctx.debug.decide("task asked to pause; opening the menu and stopping the world clock");
        status.set("lune.status.stop_game.pausing");
        return TaskStatus.RUNNING;
    }
}
