package com.etka.lune.bot;

import com.etka.lune.bot.util.BlockBreaker;
import com.etka.lune.bot.util.Vision;
import com.etka.lune.compat.Screens;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * A card's half of running beside the player: whether it was asked to, and whether this tick it
 * found anything to do.
 *
 * <p>Beside the player, a card that looks for its work does not go looking. The player is the one
 * walking and turning, so the card waits until what it wants is in the player's own view, and only
 * then asks for the controls. Mine, Chop Wood, Harvest, Loot, Kill, Explore and Find all keep one of
 * these, and all of them follow the same rules, which is why the rules live here rather than in
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
 *   <li><b>No new work while a screen is open,</b> or while the player comes first and their hands
 *       are on the controls ({@link #mayStart}). A player in their inventory or the chat box is not
 *       playing for the moment, and a card that set off then would walk them away from the screen
 *       they opened. Danger is the guard's, which does not ask.</li>
 *   <li><b>A touch drops the work, with the player first</b> ({@link #yielding}). The controls went
 *       back the moment the player touched them, and what the card was doing is let go of rather
 *       than kept for later: the player may walk off with it, and coming back to a block they left
 *       behind is the walk back to a remembered block this card never takes. Once their hands are
 *       off it looks again, and finds the same block if it is still in view.</li>
 *   <li><b>Only work the controls she may take can finish.</b> A task may leave the mouse or the
 *       keyboard to the player ({@link com.etka.lune.task.BesideOptions}). Without the keyboard she
 *       cannot walk, so a card takes only what is already within reach ({@link #withinReach});
 *       without the mouse she cannot aim or click, so a card whose work is a swing does not take
 *       any ({@link #mayUseHands}).</li>
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
     * place, and beside them only while no screen is open and, with them first, their hands are off
     * the controls.
     */
    public boolean mayStart(BotContext ctx) {
        return !on || (Screens.current(ctx.mc) == null && !ctx.controls.yielding());
    }

    /**
     * Beside the player with them first, and their hands on the controls: whatever this card had in
     * hand is let go of, and it watches.
     */
    public boolean yielding(BotContext ctx) {
        return on && ctx.controls.yielding();
    }

    /** Whether this run lets her walk to the work: always in the player's place. */
    public boolean mayWalk(BotContext ctx) {
        return !on || ctx.controls.mayTakeKeyboard();
    }

    /** Whether this run lets her aim and click: always in the player's place. */
    public boolean mayUseHands(BotContext ctx) {
        return !on || ctx.controls.mayTakeMouse();
    }

    /**
     * Whether a block is work this card can finish from where the player stands: always, when she
     * may walk to it; without the keyboard, only a block her hand reaches from here, along a line
     * nothing else is in the way of.
     */
    public boolean withinReach(BotContext ctx, BlockPos pos) {
        if (mayWalk(ctx)) {
            return true;
        }
        return ctx.player.getEyePosition().distanceToSqr(Vision.blockAimPoint(ctx, pos))
                <= BlockBreaker.REACH * BlockBreaker.REACH
                && Vision.isReachable(ctx, pos);
    }

    /** As {@link #withinReach(BotContext, BlockPos)}, for a mob: no further than {@code reach}. */
    public boolean withinReach(BotContext ctx, Entity entity, double reach) {
        return mayWalk(ctx) || ctx.player.distanceTo(entity) <= reach;
    }

    /**
     * For a card whose whole job is the hand - a meal, an item to hold, armour, a backpack, a
     * compass - asked before it reaches for anything: null while her cards may use the mouse, and
     * otherwise what the card answers instead. Failed when the task never lets her take the mouse,
     * because waiting would never end and the Fail branch is where the graph says what to do about
     * it; still running while the player, who comes first, has their hands on it.
     *
     * <p>Static, because these cards keep no {@code Beside}: they act the moment power reaches
     * them, beside the player or not, and in the player's place this always answers null.</p>
     */
    public static TaskStatus handsOnly(BotContext ctx, StatusText status) {
        if (!ctx.controls.mayTakeMouse()) {
            status.set("lune.status.beside.needs_mouse");
            return TaskStatus.FAILED;
        }
        if (!ctx.controls.mouse()) {
            status.set("lune.status.beside.waiting_for_hands");
            return TaskStatus.RUNNING;
        }
        return null;
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
