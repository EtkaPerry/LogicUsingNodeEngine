package com.etka.lune.bot.task;

import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskStatus;

import net.minecraft.client.gui.screens.PauseScreen;

/**
 * Opens the pause menu, which in singleplayer also stops the world ticking.
 *
 * <p>The useful end to an unattended run: everything is left exactly where it is, nothing decays,
 * no mob wanders in overnight, and the world is still there to look at in the morning. Leaving or
 * closing the game throws that away, which is what {@link StopGameTask} is for when it is wanted.
 *
 * <p>On a server pressing escape pauses nothing - the world keeps running and the player keeps
 * standing in it, which is the opposite of the intent - so this refuses there and points at the
 * other command.
 */
public final class PauseGameTask implements Task {

    @Override
    public boolean automaticSkillLearning() {
        return false;
    }

    private String status = "";

    @Override
    public String name() {
        return "Pause the Game";
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public TaskStatus onTick(BotContext ctx) {
        if (!ctx.mc.hasSingleplayerServer()) {
            status = "on a server the world keeps running; use Stop the Game to leave instead";
            return TaskStatus.FAILED;
        }
        if (ctx.mc.screen instanceof PauseScreen) {
            status = "paused";
            return TaskStatus.SUCCESS;
        }
        // Scheduled rather than opened inline: this runs from the client tick, and swapping the
        // screen out from under the loop that is running is how a menu opens half-initialised.
        // `true` is the pause-the-world form; the cosmetic one does not stop the clock.
        ctx.mc.schedule(() -> ctx.mc.setScreen(new PauseScreen(true)));
        ctx.debug.decide("routine asked to pause; opening the menu and stopping the world clock");
        status = "pausing";
        return TaskStatus.RUNNING;
    }
}
