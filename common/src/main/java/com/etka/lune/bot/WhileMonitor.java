package com.etka.lune.bot;

import com.etka.lune.task.runner.Guard;

/**
 * A task with a condition for whether it has work to do. TaskGraph circuits may tick it beside the
 * circuit that powered it; the monitor does not own or pause the other circuit. It is the player's
 * kind of {@link Guard}.
 */
public interface WhileMonitor extends Task, Guard<BotContext> {

    /** True when this circuit should receive its tick. */
    @Override
    boolean shouldTakeControl(BotContext ctx);

    /** Called once when control returns to the main task, so transient recovery state can stop. */
    @Override
    default void onControlReleased(BotContext ctx) {}
}
