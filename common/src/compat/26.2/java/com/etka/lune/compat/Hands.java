package com.etka.lune.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;

/**
 * What the player's hands do, on Minecraft 26.2.
 *
 * <p>26.3 gave {@code LivingEntity.swing} an animation argument, taken from the held item, and
 * moved dropping the held item from {@code LocalPlayer.drop} onto the game mode. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Hands {

    private Hands() {}

    /** Swings an arm the way vanilla does for an attack or a block hit. */
    public static void swing(LivingEntity entity, InteractionHand hand) {
        entity.swing(hand);
    }

    /** Drops the held item through the same route as the drop key; {@code all} drops the whole stack. */
    public static void dropHeld(Minecraft mc, LocalPlayer player, boolean all) {
        player.drop(all);
    }
}
