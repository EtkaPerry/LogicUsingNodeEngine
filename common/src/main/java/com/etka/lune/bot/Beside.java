package com.etka.lune.bot;

import com.etka.lune.compat.Screens;

/**
 * A card's half of running beside the player: whether it was asked to, and whether this tick it
 * found anything to do.
 *
 * <p>Beside the player, a card that looks for its work does not go looking. The player is the one
 * walking and turning, so the card waits until what it wants is in the player's own view, and only
 * then asks for the controls. Mine, Chop Wood, Harvest, Loot, Kill, Explore and Find all keep one of
 * these, and all of them follow the same two rules, which is why the rules live here rather than in
 * seven copies:</p>
 * <ul>
 *   <li><b>Nothing in sight is a wait, never a finish.</b> On its own a card reports "nothing
 *       visible left" and hands on. Beside the player that would end the card the moment the player
 *       looked away; instead it stays powered and watches.</li>
 *   <li><b>A tick that did not say it was only watching was work.</b> {@link #watch} is called on
 *       the one path where the card has nothing to do; every other path - walking to a block,
 *       swinging at it, picking up its drop - leaves the controls with Lune. Forgetting to declare
 *       a wait then costs a frozen player, which is noticed, rather than a card acting with the
 *       player's hands still on the keys, which is not.</li>
 *   <li><b>No new work while a screen is open.</b> A player in their inventory or the chat box is
 *       not playing for the moment, and a card that set off then would walk them away from the
 *       screen they opened ({@link #mayStart}). Work already in hand carries on, and danger is
 *       the guard's, which does not ask.</li>
 * </ul>
 */
public final class Beside {

    private boolean on;
    private boolean watching;

    /** Called from {@link Task#runBesidePlayer()}. */
    public void enable() {
        on = true;
    }

    public boolean on() {
        return on;
    }

    /** At the top of every tick: this tick is work until it says otherwise. */
    public void tick() {
        watching = false;
    }

    /**
     * Whether a card with nothing in hand may look for new work this tick: always in the player's
     * place, and beside them only while no screen is open.
     */
    public boolean mayStart(BotContext ctx) {
        return !on || Screens.current(ctx.mc) == null;
    }

    /**
     * Nothing of this card's work is in the player's view. Says so, keeps the card running, and
     * leaves the controls with the player.
     */
    public TaskStatus watch(StatusText status, String key, Object... args) {
        watching = true;
        status.set(key, args);
        return TaskStatus.RUNNING;
    }

    /** Whether the card is waiting rather than working, as of the tick it last took. */
    public boolean watching() {
        return on && watching;
    }

    /** {@link Task#holdsControls()} for a card that keeps one of these. */
    public boolean holdsControls() {
        return !watching();
    }
}
