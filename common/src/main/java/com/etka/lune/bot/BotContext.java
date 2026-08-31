package com.etka.lune.bot;

import com.etka.lune.bot.input.BotInput;
import com.etka.lune.bot.input.LookController;
import com.etka.lune.bot.learning.LearningSession;
import com.etka.lune.bot.learning.LearningStore;
import com.etka.lune.bot.util.OmniscientAccess;
import com.etka.lune.config.BotConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Everything a task needs, resolved and non-null. The engine builds one of these per tick and only
 * ticks tasks when the player and level actually exist, so tasks never have to null-check.
 */
public final class BotContext {

    public final Minecraft mc;
    public final LocalPlayer player;
    public final ClientLevel level;
    public final MultiPlayerGameMode gameMode;
    public final BotInput input;
    public final LookController look;
    public final BotConfig config;
    /** Shared telemetry sink; tasks write into it for the debug overlay. */
    public final DebugInfo debug;
    /** Local persistent learner; tasks may only choose among their own safe alternatives. */
    public final LearningStore learning;
    /** Bounded memory for the current run. */
    public final LearningSession learningSession;
    /** Variant selected for the active top-level task. */
    public final String learningAction;
    /**
     * Where the bot was standing when this run was started, or null before anything is running.
     *
     * <p>Held for the whole run rather than per task on purpose. A leash that re-measured from
     * wherever each node happened to begin would let a long task walk away from home one node's
     * worth at a time, and arrive somewhere nobody asked for while every individual step looked
     * perfectly well behaved.
     */
    public final BlockPos runAnchor;

    public BotContext(Minecraft mc, LocalPlayer player, ClientLevel level, MultiPlayerGameMode gameMode,
                      BotInput input, LookController look, BotConfig config, DebugInfo debug,
                      LearningStore learning, LearningSession learningSession, String learningAction,
                      BlockPos runAnchor) {
        this.mc = mc;
        this.player = player;
        this.level = level;
        this.gameMode = gameMode;
        this.input = input;
        this.look = look;
        this.config = config;
        this.debug = debug;
        this.learning = learning;
        this.learningSession = learningSession;
        this.learningAction = learningAction == null ? "default" : learningAction;
        this.runAnchor = runAnchor;
    }

    /** Prints to the action bar - transient feedback that doesn't spam chat history. */
    public void overlay(String message) {
        player.sendOverlayMessage(Component.literal(message));
    }

    /** Prints to chat, for things worth keeping: task finished, task failed. */
    public void chat(String message) {
        player.sendSystemMessage(Component.literal("[Lune] " + message));
    }

    /** Effective omniscient mining setting, including the current world's authority boundary. */
    public boolean omniscientMining() {
        return config.omniscientMining && OmniscientAccess.isAllowed(mc);
    }

    /** Effective omniscient harvesting setting, including the current world's authority boundary. */
    public boolean omniscientHarvesting() {
        return config.omniscientHarvesting && OmniscientAccess.isAllowed(mc);
    }
}
