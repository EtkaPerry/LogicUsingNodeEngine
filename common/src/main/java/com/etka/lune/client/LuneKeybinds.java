package com.etka.lune.client;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.config.BotConfig;
import com.etka.lune.platform.BuildFeatures;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Lune's keybinds, plus the per-tick entry point every loader calls. Keeping the handling here
 * rather than in the loader modules is what lets both entry points be three lines each.
 */
public final class LuneKeybinds {

    /** Opens the control panel (default <b>J</b>). */
    public static final KeyMapping OPEN =
            new KeyMapping("key.lune.open", GLFW.GLFW_KEY_J, KeyMapping.Category.MISC);

    /** Pauses or resumes the bot (default <b>K</b>); hold Shift for the emergency stop. */
    public static final KeyMapping PAUSE =
            new KeyMapping("key.lune.pause", GLFW.GLFW_KEY_K, KeyMapping.Category.MISC);

    /** Shows or hides the telemetry overlay (default <b>F6</b>). */
    public static final KeyMapping TOGGLE_DEBUG =
            new KeyMapping("key.lune.toggle_debug", GLFW.GLFW_KEY_F6, KeyMapping.Category.MISC);

    /** Developer-only approval feedback (default <b>F7</b>). */
    public static final KeyMapping LEARNING_GOOD =
            new KeyMapping("key.lune.learning_good", GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC);

    /** Developer-only rejection feedback (default <b>F8</b>). */
    public static final KeyMapping LEARNING_BAD =
            new KeyMapping("key.lune.learning_bad", GLFW.GLFW_KEY_F8, KeyMapping.Category.MISC);

    private LuneKeybinds() {}

    /** Called once per client tick by each loader: handles keys, then ticks the bot. */
    public static void clientTick(Minecraft mc) {
        if (mc.player != null) {
            while (OPEN.consumeClick()) {
                mc.setScreen(new LuneScreen());
            }
            while (PAUSE.consumeClick()) {
                boolean shift = GLFW.glfwGetKey(mc.getWindow().handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                        || GLFW.glfwGetKey(mc.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                if (shift) {
                    BotEngine.get().stopAll();
                    mc.player.sendOverlayMessage(Component.literal("[Lune] Stopped"));
                } else {
                    BotEngine engine = BotEngine.get();
                    engine.setPaused(!engine.isPaused());
                    mc.player.sendOverlayMessage(Component.literal(
                            "[Lune] " + (engine.isPaused() ? "Paused" : "Resumed")));
                }
            }
            while (TOGGLE_DEBUG.consumeClick()) {
                BotConfig config = BotConfig.get();
                config.showDebug = !config.showDebug;
                config.save();
                mc.player.sendOverlayMessage(Component.literal(
                        "[Lune] Debug overlay " + (config.showDebug ? "on" : "off")));
            }
            if (BuildFeatures.approvalFeedback()) {
                while (LEARNING_GOOD.consumeClick()) {
                    mc.player.sendOverlayMessage(Component.literal("[Lune] "
                            + BotEngine.get().recordLearningFeedback(true)));
                }
                while (LEARNING_BAD.consumeClick()) {
                    mc.player.sendOverlayMessage(Component.literal("[Lune] "
                            + BotEngine.get().recordLearningFeedback(false)));
                }
            }
        }
        BotEngine.get().tick(mc);
    }
}
