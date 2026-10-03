package com.etka.lune.bot.input;

import com.etka.lune.compat.Hands;
import com.etka.lune.compat.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * The game mode as a card sees it: every hand action a card takes goes through here - a click on
 * a block or a mob, using an item, a click in the inventory, a swing, dropping what is held,
 * closing a screen - and reaches the game only while her cards may use the mouse
 * ({@link Controls#mouse()}).
 *
 * <p>In a run in the player's place that is always, and this is the game mode with nothing in the
 * way. Beside the player it is not: the task may leave the mouse to the player, or the player may
 * come first and have their hands on it. A camera that is not hers already stops every aimed click
 * ({@link LookController#isLookingAt}), but some hand actions need no aim - eating, a shield, the
 * bucket under a fall, armour, an emergency swing - and those would otherwise reach the game on the
 * player's behalf with nobody's hand on the mouse.</p>
 *
 * <p>So a refused action does nothing and answers as the game answers a click that did nothing:
 * a break not accepted, an interaction that passed. Letting go is refused too: the player's own
 * eating or digging is not Lune's to stop, and what she started herself the game lets go of on its
 * own the moment the use and attack keys are not held. What is held is not changed here - a card
 * picks a tool and goes on to check it is in hand - so the engine puts the hotbar back after any
 * tick the mouse was not hers ({@code BotEngine}).</p>
 *
 * <p>The methods keep the game's names and arguments, so a card writes {@code ctx.gameMode.attack}
 * exactly as it would have written it to the game. {@code HandsGoThroughTheGateTest} fails the
 * build on a card that reaches past this to the game's own.</p>
 */
public final class GameModeGate {

    private final Minecraft mc;
    private final LocalPlayer player;
    private final MultiPlayerGameMode gameMode;
    private final Controls controls;

    public GameModeGate(Minecraft mc, LocalPlayer player, MultiPlayerGameMode gameMode,
                        Controls controls) {
        this.mc = mc;
        this.player = player;
        this.gameMode = gameMode;
        this.controls = controls == null ? Controls.ALL : controls;
    }

    /** Whether her cards may use the player's hands this tick. */
    public boolean mayAct() {
        return controls.mouse() && gameMode != null;
    }

    // --- the game's own, by its own names ------------------------------------------------------

    public boolean startDestroyBlock(BlockPos pos, Direction face) {
        return mayAct() && gameMode.startDestroyBlock(pos, face);
    }

    public boolean continueDestroyBlock(BlockPos pos, Direction face) {
        return mayAct() && gameMode.continueDestroyBlock(pos, face);
    }

    public void stopDestroyBlock() {
        if (mayAct()) {
            gameMode.stopDestroyBlock();
        }
    }

    public InteractionResult useItemOn(LocalPlayer user, InteractionHand hand, BlockHitResult hit) {
        return mayAct() ? gameMode.useItemOn(user, hand, hit) : InteractionResult.PASS;
    }

    public InteractionResult useItem(Player user, InteractionHand hand) {
        return mayAct() ? gameMode.useItem(user, hand) : InteractionResult.PASS;
    }

    public void releaseUsingItem(Player user) {
        if (mayAct()) {
            gameMode.releaseUsingItem(user);
        }
    }

    public void attack(Player user, Entity target) {
        if (mayAct()) {
            gameMode.attack(user, target);
        }
    }

    public InteractionResult interact(Player user, Entity target, EntityHitResult hit,
                                      InteractionHand hand) {
        return mayAct() ? gameMode.interact(user, target, hit, hand) : InteractionResult.PASS;
    }

    public void handleContainerInput(int containerId, int slot, int button, ContainerInput input,
                                     Player user) {
        if (mayAct()) {
            gameMode.handleContainerInput(containerId, slot, button, input, user);
        }
    }

    public void handlePlaceRecipe(int containerId, RecipeDisplayId recipe, boolean useMaxItems) {
        if (mayAct()) {
            gameMode.handlePlaceRecipe(containerId, recipe, useMaxItems);
        }
    }

    // --- the rest of what a hand does ----------------------------------------------------------

    /** Swings the arm, as an attack or a block hit does. */
    public void swing(InteractionHand hand) {
        if (mayAct()) {
            Hands.swing(player, hand);
        }
    }

    /** Drops what is held, by the drop key's own route; {@code all} drops the whole stack. */
    public void dropHeld(boolean all) {
        if (mayAct()) {
            Hands.dropHeld(mc, player, all);
        }
    }

    /** Stops using whatever is in hand - a meal, a shield - so the next click can be a swing. */
    public void stopUsingItem() {
        if (mayAct()) {
            player.stopUsingItem();
        }
    }

    /** Closes the screen in front of the player. Never one they opened while the mouse was theirs. */
    public void closeContainer() {
        if (mayAct()) {
            player.closeContainer();
            Screens.open(mc, null);
        }
    }

    /**
     * Holds the use key down, the way a meal is eaten, or lets it go. Through {@link HeldKeys}, so
     * it works under Toggle Use. Letting go is refused with the rest: the key may be the player's
     * own hand on the button, and a key she pressed herself is let go by the engine when the mouse
     * goes back to the player.
     */
    public void holdUse(boolean held) {
        if (mayAct() && mc.options != null) {
            HeldKeys.set(mc.options.keyUse, held);
        }
    }
}
