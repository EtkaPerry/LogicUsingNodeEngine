package com.etka.lune.platform;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Features that are intentionally different in a released installation. Keeping the check here
 * gives all loaders and common code the same release boundary without removing the learned policy
 * from the shipped bot.
 */
public final class BuildFeatures {

    private static final boolean RELEASE_BUILD = loadReleaseFlag();

    private BuildFeatures() {}

    /**
     * True only for the explicitly marked release produced by the release build. A loader's
     * environment is deliberately not used here: a developer build must remain a teaching build
     * even when it is launched through a production loader.
     */
    public static boolean releaseBuild() {
        return RELEASE_BUILD;
    }

    /** Explicit approval/rejection feedback and verdicts are developer-only. */
    public static boolean approvalFeedback() {
        return !RELEASE_BUILD;
    }

    /** Only source/developer builds may write new learning data. */
    public static boolean learningWritesEnabled() {
        return !RELEASE_BUILD;
    }

    private static boolean loadReleaseFlag() {
        try (InputStream stream = BuildFeatures.class.getResourceAsStream("/lune-build.properties")) {
            if (stream == null) {
                return false;
            }
            Properties properties = new Properties();
            properties.load(stream);
            return Boolean.parseBoolean(properties.getProperty("lune.release", "false"));
        } catch (IOException | RuntimeException ignored) {
            // A missing or malformed marker must fail safe to the developer path. The publish
            // script also verifies that it is building with -PluneRelease=true.
            return false;
        }
    }
}
