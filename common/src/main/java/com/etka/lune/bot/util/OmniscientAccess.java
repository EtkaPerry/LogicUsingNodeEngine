package com.etka.lune.bot.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * The permission boundary for the legacy omniscient mining and harvesting modes.
 *
 * <p>An integrated server is the player's own world, including when that world is opened to LAN.
 * On a dedicated server, the client only gets this access when the server has granted the player
 * vanilla's gamemaster/operator command permission. The permission is received from the server;
 * a locally edited config file is never treated as proof of authority.</p>
 */
public final class OmniscientAccess {

    private OmniscientAccess() {}

    /** True when the current world allows the legacy omniscient modes. */
    public static boolean isAllowed(Minecraft mc) {
        if (mc == null) {
            return false;
        }
        return isAllowed(mc.hasSingleplayerServer(), hasServerPermission(mc.player));
    }

    /** Pure policy decision kept separate so the boundary can be tested without a running client. */
    public static boolean isAllowed(boolean singleplayer, boolean serverOperator) {
        return singleplayer || serverOperator;
    }

    /** Vanilla operator permission, including higher admin and owner levels. */
    private static boolean hasServerPermission(LocalPlayer player) {
        return player != null
                && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
