package com.etka.lune.bot.input;

import com.etka.lune.task.BesideOptions;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Who holds the controls in a run beside the player, and the passing of them back and forth.
 *
 * <p>Beside the player the keys are the player's until a powered card has something to do, and
 * then they are Lune's - as much of them as the task lets her take ({@link BesideOptions}). The
 * mouse is the camera and the hand; the keyboard is walking, jumping, crouching and sprinting.
 * While she has the mouse:</p>
 * <ul>
 *   <li><b>The camera</b> is put back where she left it at the start of every tick, before the
 *       game reads it, so the mouse can pull the view about between ticks and change nothing she
 *       aims at. Undone rather than blocked: blocking the mouse needs a hook in each loader's mouse
 *       handler, and putting it back needs none.</li>
 *   <li><b>The hotbar slot</b> she chose is put back the same way, against the scroll wheel.</li>
 *   <li><b>Attack and use</b> are hers: a held attack would dig whatever her head swings across,
 *       and a held use would place blocks wherever it points. Every click on the way to the game -
 *       those two, pick block, drop, swap hands, the hotbar keys, the inventory - is dropped.</li>
 * </ul>
 * <p>While she has the keyboard, movement is hers through {@link BotClientInput}, which asks the
 * engine and needs nothing from here. What she may not take stays the player's the whole time:
 * {@link BotClientInput} passes the keys through, {@link LookController} leaves the camera alone
 * and {@link GameModeGate} keeps her hands off.</p>
 *
 * <p>Who comes first decides what the player's touch means. With Lune first it is undone, as
 * above, until her card is done; the pause key is the player's way back. With the player first,
 * touching anything she would take - a movement key, the mouse, a click, the scroll wheel - hands
 * everything back at once, and she does not take it again until the player has left it alone for
 * {@link #QUIET_TICKS}.</p>
 *
 * <p>Handing back gives the player the hotbar slot they had, and lets go of the use key if she was
 * holding it. Nothing else is restored: the camera stays where the work left it, because snapping
 * the view back to where the player had been looking, from somewhere they have since been carried
 * away from, is more disorienting than the turn itself.</p>
 *
 * <p>The pause key is never touched. It is the player's way to take everything back at once.</p>
 */
public final class Handover {

    /**
     * Ticks with nothing to do before the keys go back. A run moving from one card to the next
     * spends a tick with no card at all, and handing the keys over for that one tick would flick
     * the hotbar and stop a sprint for nothing.
     */
    public static final int RELEASE_TICKS = 3;

    /**
     * With the player first, how long their hands must have been off everything she would take
     * before she takes it. A second: long enough that the pause between two flicks of the mouse is
     * not an opening, short enough that stopping to look at something is.
     */
    public static final int QUIET_TICKS = 20;

    /**
     * How far the view has to have turned between her tick and the next to be the mouse, in
     * degrees. Anything the mouse moves is far more; this only keeps rounding from counting.
     */
    private static final float TURN_EPSILON = 0.01F;

    public enum Change { NONE, TAKE, GIVE }

    // The run's own choices, read once as it starts.
    private boolean playerFirst;
    private boolean takesMouse = true;
    private boolean takesKeyboard = true;

    private boolean lune;
    private int idleTicks;
    /** With the player first: ticks since they last touched anything she would take. */
    private int quietTicks = QUIET_TICKS;

    /** The player these snapshots are of; a respawn or a dimension change is a new one. */
    private LocalPlayer owner;
    /** The hotbar slot the player had when she took over, to give back. */
    private int playerSlot = -1;
    /** The view and the hand as her tick left them, whoever held them: what a touch is read against. */
    private LocalPlayer seen;
    private float seenYaw;
    private float seenPitch;
    private int seenSlot = -1;
    /** Whether the use key was hers when her tick ended, so holding it is not the player's click. */
    private boolean luneUse;

    /**
     * A run starts: its own choices, and nobody holding anything yet. A player whose hands are
     * still on the keys from before the run is noticed on the first tick, not assumed.
     */
    public void start(BesideOptions options) {
        BesideOptions chosen = options == null ? new BesideOptions() : options.copy();
        playerFirst = chosen.playerFirst;
        takesMouse = chosen.mouse;
        takesKeyboard = chosen.keyboard;
        forget();
        quietTicks = QUIET_TICKS;
        seen = null;
    }

    /** Whether Lune holds the controls - as much of them as she may take. */
    public boolean lune() {
        return lune;
    }

    public boolean playerFirst() {
        return playerFirst;
    }

    /** Whether this run lets her take the mouse: the camera, and what is held and used. */
    public boolean takesMouse() {
        return takesMouse;
    }

    /** Whether this run lets her take the keyboard: walking, jumping, crouching, sprinting. */
    public boolean takesKeyboard() {
        return takesKeyboard;
    }

    /**
     * With the player first: their hands are on what she would take, or were less than
     * {@link #QUIET_TICKS} ago. Nothing is taken while this is so.
     */
    public boolean yielding() {
        return playerFirst && quietTicks < QUIET_TICKS;
    }

    /**
     * Counts one tick of the player's hands, before the game reads them.
     *
     * @param touched whether the player used anything she would take since her last tick
     * @return true when this touch hands everything back at once: the player comes first, and she
     *         was holding the controls
     */
    public boolean observe(boolean touched) {
        if (!touched) {
            if (quietTicks < QUIET_TICKS) {
                quietTicks++;
            }
            return false;
        }
        quietTicks = 0;
        return playerFirst && lune;
    }

    /**
     * Decides, from whether the run wants the controls after the tick it has just taken, whether
     * they change hands. Taking is immediate; giving back waits {@link #RELEASE_TICKS}. Nothing is
     * taken while the player comes first and their hands are on the controls.
     */
    public Change update(boolean wanted) {
        if (wanted && !yielding()) {
            idleTicks = 0;
            if (!lune) {
                lune = true;
                return Change.TAKE;
            }
            return Change.NONE;
        }
        if (!lune || ++idleTicks < RELEASE_TICKS) {
            return Change.NONE;
        }
        lune = false;
        idleTicks = 0;
        return Change.GIVE;
    }

    /**
     * Lune takes the controls, at the end of the tick in which a card first acted.
     *
     * @param slotBefore the hotbar slot as the player had it before that tick - the card may
     *                   already have picked a tool, and it is the player's choice that is given back
     * @param useBefore  whether use was down before that tick: still down after it, it is the
     *                   player's hand on the button rather than hers, and it is let go
     */
    public void takeOver(Minecraft mc, int slotBefore, boolean useBefore) {
        owner = mc.player;
        if (takesMouse) {
            playerSlot = slotBefore;
            Options options = mc.options;
            boolean hers = options.keyUse.isDown() && !useBefore;
            HeldKeys.set(options.keyUse, hers);
            HeldKeys.set(options.keyAttack, false);
            dropClicks(options);
        }
        remember(mc);
    }

    /**
     * At the end of each of her ticks, whoever holds the controls: the view and the hand as the
     * tick left them. Put back at the start of the next one while the mouse is hers, and read
     * against in any case to tell whether the player touched it in between.
     */
    public void remember(Minecraft mc) {
        LocalPlayer player = mc == null ? null : mc.player;
        if (player == null) {
            seen = null;
            return;
        }
        seen = player;
        seenYaw = player.getYRot();
        seenPitch = player.getXRot();
        seenSlot = player.getInventory().getSelectedSlot();
        // Nothing the player does reaches the key between her tick and this line, so whatever it
        // says now is what her card left it as.
        luneUse = lune && takesMouse && player == owner && mc.options.keyUse.isDown();
    }

    /**
     * Whether the player has used, since her last tick, anything this run lets her take. Read at
     * the start of a client tick, before the game reads the keys and the mouse.
     *
     * <p>Movement is the movement keys; Shift only while crouching is held rather than toggled,
     * because a toggled crouch reads as held for as long as it is on, and Sprint never, since it
     * moves nobody on its own. The mouse is the view having turned, the hotbar slot having moved,
     * or a click being held - attack, use (unless she holds it herself), pick block - and the keys
     * that work the hand: the hotbar keys, drop, swap and the inventory.</p>
     */
    public boolean touchedByPlayer(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player != seen) {
            return false;
        }
        Options options = mc.options;
        if (takesKeyboard && (options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown() || options.keyJump.isDown()
                || (options.keyShift.isDown() && !options.toggleCrouch().get()))) {
            return true;
        }
        if (!takesMouse) {
            return false;
        }
        if (Math.abs(Mth.wrapDegrees(player.getYRot() - seenYaw)) > TURN_EPSILON
                || Math.abs(player.getXRot() - seenPitch) > TURN_EPSILON
                || player.getInventory().getSelectedSlot() != seenSlot) {
            return true;
        }
        if (options.keyAttack.isDown() || (options.keyUse.isDown() && !luneUse)
                || options.keyPickItem.isDown() || options.keyDrop.isDown()
                || options.keySwapOffhand.isDown() || options.keyInventory.isDown()) {
            return true;
        }
        for (KeyMapping slot : options.keyHotbarSlots) {
            if (slot.isDown()) {
                return true;
            }
        }
        return false;
    }

    /**
     * At the start of a client tick, before the game reads the keys: whatever the player did with
     * the mouse since her last tick is undone, when the mouse is hers.
     */
    public void reassert(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (!takesMouse || player == null || player != owner || player != seen) {
            return;
        }
        player.setYRot(seenYaw);
        player.setXRot(seenPitch);
        if (seenSlot >= 0) {
            player.getInventory().setSelectedSlot(seenSlot);
        }
        Options options = mc.options;
        HeldKeys.set(options.keyUse, luneUse);
        HeldKeys.set(options.keyAttack, false);
        dropClicks(options);
    }

    /** Lune hands the controls back: the player's hotbar slot, and the use key let go. */
    public void giveBack(Minecraft mc) {
        LocalPlayer player = mc == null ? null : mc.player;
        if (takesMouse && player != null && player == owner && playerSlot >= 0) {
            player.getInventory().setSelectedSlot(playerSlot);
        }
        if (luneUse && mc != null && mc.options != null) {
            HeldKeys.set(mc.options.keyUse, false);
        }
        forget();
        // What she left is what the player now has in front of them; their next touch is read
        // against it rather than against her last tick.
        remember(mc);
    }

    /** Drops what she holds without touching the game: the run ended, or the player is gone. */
    public void forget() {
        lune = false;
        idleTicks = 0;
        owner = null;
        playerSlot = -1;
        luneUse = false;
    }

    /** Every click the player made on the way to the game that would act on the world or the bag. */
    private static void dropClicks(Options options) {
        drain(options.keyAttack);
        drain(options.keyUse);
        drain(options.keyPickItem);
        drain(options.keyDrop);
        drain(options.keySwapOffhand);
        drain(options.keyInventory);
        for (KeyMapping slot : options.keyHotbarSlots) {
            drain(slot);
        }
    }

    private static void drain(KeyMapping key) {
        while (key.consumeClick()) {
            // Each consumed click is one the game never sees.
        }
    }
}
