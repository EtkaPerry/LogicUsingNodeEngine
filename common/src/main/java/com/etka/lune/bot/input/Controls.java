package com.etka.lune.bot.input;

/**
 * Which of the player's controls Lune's cards may use, as the engine answers it this tick.
 *
 * <p>In a run in the player's place every answer is yes, as it always was. Beside the player they
 * follow the task's own choices - who comes first, and whether she may take the mouse, the keyboard
 * or both ({@link com.etka.lune.task.BesideOptions}) - and the player's hands.</p>
 *
 * <p>The keyboard needs no question here: movement only reaches the game through
 * {@link BotClientInput}, which already asks the engine. The mouse does, because a card turns the
 * head and clicks on its own. So {@link LookController} asks before it turns the head, a card is
 * aimed at nothing while the camera is not hers, and the few hand actions that need no aim ask
 * {@link #mouse()} themselves.</p>
 */
public interface Controls {

    /** In the player's place: everything, always, and the player never waited for. */
    Controls ALL = new Controls() {
        @Override
        public boolean mayTakeMouse() {
            return true;
        }

        @Override
        public boolean mayTakeKeyboard() {
            return true;
        }

        @Override
        public boolean yielding() {
            return false;
        }
    };

    /** Whether this run lets her take the mouse at all: the camera, and what is held and used. */
    boolean mayTakeMouse();

    /** Whether this run lets her take the keyboard at all: walking, jumping, crouching, sprinting. */
    boolean mayTakeKeyboard();

    /**
     * With the player first: their hands are on something she would take, or were a moment ago.
     * Nothing new is started, and work already in hand waits or is let go.
     */
    boolean yielding();

    /**
     * Whether her cards may use the mouse this tick: turn the head, click, use an item, choose
     * what is held.
     */
    default boolean mouse() {
        return mayTakeMouse() && !yielding();
    }
}
