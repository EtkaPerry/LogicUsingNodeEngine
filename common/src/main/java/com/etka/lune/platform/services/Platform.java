package com.etka.lune.platform.services;

import java.nio.file.Path;

/**
 * The handful of things the bot genuinely cannot do in loader-agnostic code. Deliberately tiny -
 * Lune is a client-side mod driving vanilla systems, so nearly everything lives in {@code common}.
 */
public interface Platform {

    /** Human-readable loader name, shown on the Main tab. */
    String getPlatformName();

    /** Whether another mod is present (used for optional compat checks). */
    boolean isModLoaded(String modId);

    /** True in a development environment. */
    boolean isDevelopment();

    /** The loader's config directory; Lune writes {@code lune.json} here. */
    Path getConfigDir();
}
