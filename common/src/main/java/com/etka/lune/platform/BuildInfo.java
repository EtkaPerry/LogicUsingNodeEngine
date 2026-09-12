package com.etka.lune.platform;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * What this particular jar is: version, the Minecraft it was built against, who wrote it, and
 * whether it is a release.
 *
 * <p>Read from {@code lune-build.properties}, which the build fills in from the same
 * {@code gradle.properties} values that go into the loader's own metadata. That indirection is the
 * point: a version number typed into Java is a version number that will disagree with the jar's
 * file name within two releases, and the first place anybody reads it is a bug report.</p>
 */
public final class BuildInfo {

    private static final String UNKNOWN = "unknown";
    private static final Properties PROPERTIES = load();

    private BuildInfo() {}

    /** Lune's own version, e.g. {@code 0.3}. */
    public static String version() {
        return PROPERTIES.getProperty("lune.version", UNKNOWN);
    }

    /** The Minecraft version this jar was built against. */
    public static String minecraftVersion() {
        return PROPERTIES.getProperty("lune.minecraft", UNKNOWN);
    }

    public static String author() {
        return PROPERTIES.getProperty("lune.author", UNKNOWN);
    }

    /**
     * A build flag, false unless the file says otherwise.
     *
     * <p>Package-visible because the only flag is the release marker, and what that marker means is
     * {@link BuildFeatures}'s to say rather than any caller's.</p>
     */
    static boolean flag(String key) {
        return Boolean.parseBoolean(PROPERTIES.getProperty(key, "false"));
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream stream = BuildInfo.class.getResourceAsStream("/lune-build.properties")) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing or malformed marker must fail safe: no version to show, and no release
            // flag, which is the developer path. The publish script verifies the real thing.
        }
        return properties;
    }
}
