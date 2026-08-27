package com.etka.lune.bot;

/** Outcome of a single {@link Task#onTick} call. */
public enum TaskStatus {
    /** Still working; tick me again next tick. */
    RUNNING,
    /** Finished successfully; move on to the next queued task. */
    SUCCESS,
    /** Could not continue; the queue is halted and the reason is reported to the player. */
    FAILED
}
