package com.etka.lune.bot;

/**
 * A task with a condition for whether it has work to do. TaskGraph circuits may tick it beside the
 * circuit that powered it; the monitor does not own or pause the other circuit.
 */
public interface WhileMonitor extends Task {

    /** True when this circuit should receive its tick. */
    boolean shouldTakeControl(BotContext ctx);

    /** Called once when control returns to the main task, so transient recovery state can stop. */
    default void onControlReleased(BotContext ctx) {}
}
