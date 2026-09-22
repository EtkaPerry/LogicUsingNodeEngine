package com.etka.lune.waypoint.external;

/**
 * Where JourneyMap's client API is kept once JourneyMap hands it over.
 *
 * <p>Held as a plain {@code Object} on purpose. This class is reached from code that runs whether
 * or not JourneyMap is installed, and a field of the API's own type would make loading this class
 * depend on JourneyMap's being there. The one place that casts it back is
 * {@link JourneyMapAccess}, which is only ever loaded after {@link #attached()} answered yes.</p>
 */
public final class JourneyMapBridge {

    private static volatile Object api;

    private JourneyMapBridge() {}

    /** Called by {@link LuneJourneyMapPlugin}, from JourneyMap's own initialisation. */
    static void attach(Object clientApi) {
        api = clientApi;
    }

    static boolean attached() {
        return api != null;
    }

    static Object api() {
        return api;
    }
}
