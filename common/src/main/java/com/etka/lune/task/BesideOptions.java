package com.etka.lune.task;

/**
 * How a task that runs beside the player shares the controls with them - see
 * {@link TaskGraph#beside}.
 *
 * <p>Two questions, both the player's to answer for each task, because they are statements about
 * the job: a guard that bats back a Ghast's fireball wants the mouse and nothing else, and wants it
 * at once; a companion mining what the player walks past may be told to wait until the player's
 * hands are off the controls.</p>
 * <ul>
 *   <li><b>Who comes first</b> when both want the controls. Lune, and a card with work in sight
 *       takes them at once and keeps them until it is done; the pause key is the player's way back.
 *       The player, and she takes them only once the player has left them alone for a moment, and
 *       lets go the moment the player touches them.</li>
 *   <li><b>What she may take.</b> The mouse - where the player looks, and what they hold and use -
 *       and the keyboard - walking, jumping, crouching and sprinting. Never neither: a run beside
 *       the player that may take nothing is a run that can do nothing.</li>
 * </ul>
 *
 * <p>Saved with the task, and read once as a run starts, like the switch. Null on every task saved
 * before it existed, which {@code TaskStore.normalize} fills with what those tasks always did: Lune
 * first, with both.</p>
 */
public final class BesideOptions {

    /** Whether the player's hands come first. False on every task saved before there was a choice. */
    public boolean playerFirst;
    /** Whether Lune may take the mouse: the camera, both buttons, the hotbar and the hand keys. */
    public boolean mouse = true;
    /** Whether Lune may take the keyboard: walking, jumping, crouching and sprinting. */
    public boolean keyboard = true;

    public BesideOptions() {}

    public BesideOptions(boolean playerFirst, boolean mouse, boolean keyboard) {
        this.playerFirst = playerFirst;
        this.mouse = mouse;
        this.keyboard = keyboard;
        normalize();
    }

    public BesideOptions copy() {
        return new BesideOptions(playerFirst, mouse, keyboard);
    }

    /** Both off is a file somebody edited by hand; it reads as both, which is what it meant before. */
    public BesideOptions normalize() {
        if (!mouse && !keyboard) {
            mouse = true;
            keyboard = true;
        }
        return this;
    }

    /** The choice every task had before there was one: Lune first, with everything. */
    public boolean isDefault() {
        return !playerFirst && mouse && keyboard;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BesideOptions that && playerFirst == that.playerFirst
                && mouse == that.mouse && keyboard == that.keyboard;
    }

    @Override
    public int hashCode() {
        return (playerFirst ? 4 : 0) | (mouse ? 2 : 0) | (keyboard ? 1 : 0);
    }
}
