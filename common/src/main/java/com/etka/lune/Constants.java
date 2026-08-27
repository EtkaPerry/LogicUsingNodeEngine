package com.etka.lune;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and identifier helpers. Lives in {@code common} so every loader and every
 * subsystem references the same mod id and logger.
 */
public final class Constants {

    public static final String MOD_ID = "lune";
    public static final String MOD_NAME = "Lune";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {}

    /** Builds a {@code lune:path} {@link Identifier}. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Builds a {@code namespace:path} {@link Identifier}. */
    public static Identifier id(String namespace, String path) {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
}
