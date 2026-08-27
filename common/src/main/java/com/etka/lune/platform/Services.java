package com.etka.lune.platform;

import com.etka.lune.Constants;
import com.etka.lune.platform.services.Platform;

import java.util.ServiceLoader;

/**
 * Service-loader lookup for the loader-specific {@link Platform} implementation. Each loader module
 * ships a {@code META-INF/services} entry pointing at its own implementation.
 */
public final class Services {

    public static final Platform PLATFORM = load(Platform.class);

    private Services() {}

    private static <T> T load(Class<T> clazz) {
        T loaded = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loaded, clazz.getSimpleName());
        return loaded;
    }
}
