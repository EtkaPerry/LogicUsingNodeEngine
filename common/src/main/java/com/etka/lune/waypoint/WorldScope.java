package com.etka.lune.waypoint;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Which world the client is in, as somewhere to keep a file about it.
 *
 * <p>Single-player files live below the world root, next to the world data. That is deliberate:
 * copying or moving a world copies them too, while opening another world cannot expose the old
 * world's coordinates. Multiplayer has no client-side world folder, so each server address gets
 * files of its own under the config directory, named by a hash of the address as the safest
 * identity available to a client-only mod.</p>
 *
 * @param cacheKey     tells one scope from the next in memory; never written anywhere
 * @param localDir     the world's own {@code lune} folder, or null on a server
 * @param remoteStem   the per-server file stem under the config directory, or null in single-player
 * @param persistedKey what a remote file records inside itself, so a copied file fails closed
 */
public record WorldScope(String cacheKey, Path localDir, Path remoteStem, String persistedKey) {

    private static final String WORLD_DATA_DIR = Constants.MOD_ID;
    private static final String REMOTE_DIR = Constants.MOD_ID + "-waypoints";

    public boolean local() {
        return localDir != null;
    }

    /**
     * Where one of this world's files goes.
     *
     * @param localName    the file name inside the world's own folder
     * @param remoteSuffix what follows the hashed stem on a server; the waypoint file's is plain
     *                     {@code .json}, because that is the name it has always had
     */
    public Path file(String localName, String remoteSuffix) {
        return local() ? localDir.resolve(localName)
                : remoteStem.resolveSibling(remoteStem.getFileName() + remoteSuffix);
    }

    /** The scope for the world the client is in, or null between worlds. */
    public static WorldScope current() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }

        // Vanilla's isSingleplayer() - a server of our own that is not open to LAN - left in
        // 26.2; this is what its body was.
        IntegratedServer singleplayerServer = mc.getSingleplayerServer();
        if (singleplayerServer != null && !singleplayerServer.isPublished()) {
            try {
                Path worldRoot = singleplayerServer.getWorldPath(LevelResource.ROOT)
                        .toAbsolutePath()
                        .normalize();
                // The absolute path is only an in-memory cache key. The portable identity is the
                // document UUID stored in the copied world folder itself.
                return new WorldScope("singleplayer:" + worldRoot, worldRoot.resolve(WORLD_DATA_DIR),
                        null, "singleplayer-world");
            } catch (RuntimeException e) {
                Constants.LOG.warn("Could not locate the current single-player world", e);
                return null;
            }
        }

        String persistedKey = "multiplayer:" + serverAddress(mc);
        Path stem = Services.PLATFORM.getConfigDir().resolve(REMOTE_DIR)
                .resolve("remote-" + sha256(persistedKey));
        return new WorldScope("remote:" + persistedKey, null, stem, persistedKey);
    }

    private static String serverAddress(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return server.ip.trim().toLowerCase(Locale.ROOT);
        }

        if (mc.getConnection() != null && mc.getConnection().getConnection() != null) {
            SocketAddress remote = mc.getConnection().getConnection().getRemoteAddress();
            if (remote != null) {
                return remote.toString().trim().toLowerCase(Locale.ROOT);
            }
        }
        return "unknown-server";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format(Locale.ROOT, "%02x", b));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required by every Java runtime, but keep a deterministic fallback if a
            // non-standard runtime ever violates that guarantee.
            return Integer.toHexString(value.hashCode());
        }
    }
}
