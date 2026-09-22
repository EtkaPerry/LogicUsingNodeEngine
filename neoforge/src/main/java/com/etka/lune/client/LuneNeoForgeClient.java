package com.etka.lune.client;

import com.etka.lune.Constants;
import com.etka.lune.client.command.LuneChatCommand;
import com.etka.lune.client.gui.DebugOverlay;
import com.etka.lune.compat.Screens;
import com.etka.lune.platform.BuildFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only NeoForge setup - registers the keybinds, pumps the bot's tick, and draws the debug
 * overlay. All three delegate straight into {@code common}.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public class LuneNeoForgeClient {

    public LuneNeoForgeClient() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) ->
                LuneKeybinds.clientTick(Minecraft.getInstance()));

        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) ->
                DebugOverlay.render(event.getGuiGraphics()));

        // Submits rather than a buffer source: the collector and the render state are what every
        // Minecraft version Lune builds for has in common (MultiBufferSource left in 26.2, and the
        // camera accessor on GameRenderer was renamed in the same release).
        NeoForge.EVENT_BUS.addListener((SubmitCustomGeometryEvent event) ->
                WorldActionOverlay.render(event.getPoseStack(), event.getLevelRenderState(),
                        event.getSubmitNodeCollector()));

        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
                event.getDispatcher().register(LuneChatCommand.build(LuneChatCommand.VANILLA)));

        NeoForge.EVENT_BUS.addListener(LuneNeoForgeClient::replaceTestWorldButton);
    }

    /**
     * Minecraft's development-only button normally creates a creative Redstone Ready flat world.
     * For Lune development, a normal survival world is more useful, while commands remain handy
     * for arranging test scenarios.
     */
    private static void replaceTestWorldButton(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen titleScreen)) {
            return;
        }

        Button testWorldButton = event.getListenersList().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> "Create Test World".equals(button.getMessage().getString()))
                .findFirst()
                .orElse(null);
        if (testWorldButton == null) {
            return;
        }

        event.removeListener(testWorldButton);
        Button survivalWorldButton = Button.builder(testWorldButton.getMessage(), button -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    CreateWorldScreen.openFresh(minecraft, () -> Screens.open(minecraft, titleScreen));
                    if (Screens.current(minecraft) instanceof CreateWorldScreen createWorldScreen) {
                        createWorldScreen.getUiState().setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
                        createWorldScreen.getUiState().setAllowCommands(true);
                    }
                })
                .bounds(testWorldButton.getX(), testWorldButton.getY(), testWorldButton.getWidth(), testWorldButton.getHeight())
                .build();
        survivalWorldButton.active = testWorldButton.active;
        survivalWorldButton.visible = testWorldButton.visible;
        event.addListener(survivalWorldButton);
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(LuneKeybinds.OPEN);
        event.register(LuneKeybinds.PAUSE);
        event.register(LuneKeybinds.TOGGLE_DEBUG);
        if (BuildFeatures.approvalFeedback()) {
            event.register(LuneKeybinds.LEARNING_GOOD);
            event.register(LuneKeybinds.LEARNING_BAD);
        }
    }
}
