package com.etka.lune.task.runner;

import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.bot.TaskStatus;

/**
 * One card at work, as the runner of a graph sees it - whoever's body the card moves.
 *
 * <p>The player's cards are {@link com.etka.lune.bot.Task}s: steps handed the player's context. A
 * Lumi's cards are steps handed hers, on the server. {@link GraphRun} needs nothing more from a card
 * than what is here, which is what lets one runner drive both and keeps the meaning of a graph from
 * drifting between them.</p>
 *
 * @param <C> what a card is handed each tick
 */
public interface Step<C> {

    /** Called once before the first {@link #tick}. */
    void start(C ctx);

    /** One tick of work. */
    TaskStatus tick(C ctx);

    /** Called when the card finishes, fails, or is cancelled. */
    void stop(C ctx);

    /** One-line live detail, as a key and its arguments; see {@link StatusText}. */
    default StatusText statusLine() {
        return StatusText.EMPTY;
    }

    /** A bounded amount the panel can draw as a progress bar, or {@code null} for open-ended work. */
    default TaskProgress progress() {
        return null;
    }

    /** Whether the last run did useful work; a card on repeat that found nothing should not spin. */
    default boolean madeProgress() {
        return true;
    }

    /** Whether the card needs the body after the tick it has just taken. */
    default boolean holdsControls() {
        return true;
    }
}
