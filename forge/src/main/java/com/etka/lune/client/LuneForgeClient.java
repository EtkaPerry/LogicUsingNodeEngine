package com.etka.lune.client;

import com.etka.lune.Constants;
import com.etka.lune.LuneForgeNetwork;
import com.etka.lune.bot.util.ServerAccess;
import com.etka.lune.client.command.LuneChatCommand;
import com.etka.lune.client.gui.DebugOverlay;
import com.etka.lune.compat.Screens;
import com.etka.lune.platform.BuildFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;

/**
 * Client-only Forge setup - registers the keybinds, pumps the bot's tick, draws the debug overlay,
 * and lets the client hear a server's rules. All of them delegate straight into {@code common}.
 *
 * <p>Forge 26.1 runs on EventBus 7, where every event owns a static {@code BUS} rather than being
 * posted to one shared bus, so this registers per event instead of NeoForge's {@code EVENT_BUS}.
 */
public final class LuneForgeClient {

    private LuneForgeClient() {}

    public static void init() {
        RegisterKeyMappingsEvent.BUS.addListener(LuneForgeClient::onRegisterKeyMappings);

        RegisterClientCommandsEvent.BUS.addListener(event ->
                event.getDispatcher().register(LuneChatCommand.build(LuneChatCommand.VANILLA)));

        // Start as well as end: a run beside the player keeps the player's clicks from the game while
        // Lune has the controls, and the game reads them before the end of the tick.
        TickEvent.ClientTickEvent.Pre.BUS.addListener(event ->
                LuneKeybinds.clientTickStart(Minecraft.getInstance()));
        TickEvent.ClientTickEvent.Post.BUS.addListener(event ->
                LuneKeybinds.clientTick(Minecraft.getInstance()));

        // 26.1's HUD is a layer stack rather than a render event; adding to the root stack appends,
        // which puts the overlay last, matching the Fabric module's addLast.
        AddGuiOverlayLayersEvent.BUS.addListener(event -> event.getLayeredDraw()
                .add(Constants.id("debug_overlay"),
                        (extractor, deltaTracker) -> {
                            DebugOverlay.render(extractor);
                            // Forge 26 has no level-render stage event; keep the action visible
                            // through the projected HUD fallback on this loader.
                            WorldActionOverlay.renderHud(extractor);
                        }));

        ScreenEvent.Init.Post.BUS.addListener(LuneForgeClient::replaceTestWorldButton);

        // A server's answer, and the way to ask it: see ServerAccess. The channel is one per side
        // and built in LuneForgeNetwork; only its client end is plugged in here.
        LuneForgeNetwork.onClient(ServerAccess::receive);
        ServerAccess.install(new ServerAccess.Link() {
            @Override
            public boolean canSend(CustomPacketPayload.Type<?> type) {
                ClientPacketListener listener = Minecraft.getInstance().getConnection();
                return listener != null && LuneForgeNetwork.serverHasLune(listener.getConnection());
            }

            @Override
            public void send(CustomPacketPayload payload) {
                ClientPacketListener listener = Minecraft.getInstance().getConnection();
                if (listener != null) {
                    LuneForgeNetwork.sendToServer(listener.getConnection(), payload);
                }
            }
        });
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(LuneKeybinds.OPEN);
        event.register(LuneKeybinds.PAUSE);
        event.register(LuneKeybinds.TOGGLE_DEBUG);
        if (BuildFeatures.approvalFeedback()) {
            event.register(LuneKeybinds.LEARNING_GOOD);
            event.register(LuneKeybinds.LEARNING_BAD);
        }
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
}
