package com.etka.lune.bot.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;

/**
 * Who holds the controls in a run beside the player, and the passing of them back and forth.
 *
 * <p>Beside the player the keys are the player's until a powered card has something to do, and
 * then they are Lune's - all of them, because a ghast's fireball is not batted back by a head the
 * player is still turning. So while she has them:</p>
 * <ul>
 *   <li><b>Movement</b> is hers through {@link BotClientInput}, as in any run.</li>
 *   <li><b>The camera</b> is put back where she left it at the start of every tick, before the
 *       game reads it, so the mouse can pull the view about between ticks and change nothing she
 *       aims at. Undone rather than blocked: blocking the mouse needs a hook in each loader's mouse
 *       handler, and putting it back needs none.</li>
 *   <li><b>The hotbar slot</b> she chose is put back the same way, against the scroll wheel.</li>
 *   <li><b>Attack and use</b> are hers: a held attack would dig whatever her head swings across,
 *       and a held use would place blocks wherever it points. Every click on the way to the game -
 *       those two, pick block, drop, swap hands, the hotbar keys, the inventory - is dropped.</li>
 * </ul>
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

    public enum Change { NONE, TAKE, GIVE }

    private boolean lune;
    private int idleTicks;

    /** The player these snapshots are of; a respawn or a dimension change is a new one. */
    private LocalPlayer owner;
    /** The hotbar slot the player had when she took over, to give back. */
    private int playerSlot = -1;
    private float luneYaw;
    private float lunePitch;
    private int luneSlot = -1;
    private boolean luneUse;

    /** Whether Lune holds the controls. */
    public boolean lune() {
        return lune;
    }

    /**
     * Decides, from whether the run wants the controls after the tick it has just taken, whether
     * they change hands. Taking is immediate; giving back waits {@link #RELEASE_TICKS}.
     */
    public Change update(boolean wanted) {
        if (wanted) {
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
        playerSlot = slotBefore;
        Options options = mc.options;
        boolean hers = options.keyUse.isDown() && !useBefore;
        HeldKeys.set(options.keyUse, hers);
        HeldKeys.set(options.keyAttack, false);
        dropClicks(options);
        remember(mc);
    }

    /** At the end of each tick she holds the controls: what to put back at the start of the next. */
    public void remember(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player != owner) {
            return;
        }
        luneYaw = player.getYRot();
        lunePitch = player.getXRot();
        luneSlot = player.getInventory().getSelectedSlot();
        // Nothing the player does reaches the key between her tick and this line, so whatever it
        // says now is what her card left it as.
        luneUse = mc.options.keyUse.isDown();
    }

    /**
     * At the start of a client tick, before the game reads the keys: whatever the player did with
     * the controls since her last tick is undone.
     */
    public void reassert(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || player != owner) {
            return;
        }
        player.setYRot(luneYaw);
        player.setXRot(lunePitch);
        if (luneSlot >= 0) {
            player.getInventory().setSelectedSlot(luneSlot);
        }
        Options options = mc.options;
        HeldKeys.set(options.keyUse, luneUse);
        HeldKeys.set(options.keyAttack, false);
        dropClicks(options);
    }

    /** Lune hands the controls back: the player's hotbar slot, and the use key let go. */
    public void giveBack(Minecraft mc) {
        LocalPlayer player = mc == null ? null : mc.player;
        if (player != null && player == owner && playerSlot >= 0) {
            player.getInventory().setSelectedSlot(playerSlot);
        }
        if (luneUse && mc != null && mc.options != null) {
            HeldKeys.set(mc.options.keyUse, false);
        }
        forget();
    }

    /** Drops everything without touching the game: the run ended, or the player is gone. */
    public void forget() {
        lune = false;
        idleTicks = 0;
        owner = null;
        playerSlot = -1;
        luneSlot = -1;
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
