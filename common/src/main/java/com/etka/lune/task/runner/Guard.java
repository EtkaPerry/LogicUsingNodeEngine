package com.etka.lune.task.runner;

/**
 * A card with a condition for whether it has work to do - what stands on a While pin, or a guard on
 * an Always branch. Its circuit is ticked only while {@link #shouldTakeControl} says so; it never
 * owns or pauses the circuit it guards.
 *
 * <p>Only the two questions are declared here. The rest is {@link Step}'s, so a card that is both a
 * player's task and a guard inherits each answer from one place.</p>
 */
public interface Guard<C> extends Step<C> {

    /** True when this circuit should receive its tick. */
    boolean shouldTakeControl(C ctx);

    /** Called once when control returns to the guarded card, so transient recovery state can stop. */
    default void onControlReleased(C ctx) {}
}
