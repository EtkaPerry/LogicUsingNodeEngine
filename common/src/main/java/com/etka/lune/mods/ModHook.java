package com.etka.lune.mods;

import com.etka.lune.Constants;
import com.etka.lune.platform.Services;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * A hook into a mod Lune does not compile against, resolved once and logged once.
 *
 * <p>The mods Lune cooperates with - backpacks, compasses, accessory slots, the map mods - mostly
 * publish no API artifact Lune could build on for this Minecraft version, so their public classes
 * are looked up by name instead. Every name a hook uses is written down in one place, in its
 * {@link #resolve()}, and was read out of the real jar with {@code javap} or out of the mod's
 * source for this Minecraft version before it was written there. Resolution happens once, the
 * first time anybody asks, because a lookup that fails will fail identically every frame after.</p>
 *
 * <p>Two kinds of failure are kept apart on purpose. A class or member that is not where it was
 * means the mod has changed shape, and the hook is switched off for the session with one warning
 * in the log. An ordinary exception while using it - the other mod rebuilding a list on another
 * thread, say - is a bad moment rather than a bad hook, so that one call answers with its fallback
 * and the next call asks again.</p>
 *
 * <p>Not to be confused with {@code com.etka.lune.compat}, which is about Minecraft versions.</p>
 */
public abstract class ModHook {

    private enum State { UNTRIED, READY, BROKEN }

    private State state = State.UNTRIED;

    /** The mod's name for the log, in English like the rest of the log. */
    protected abstract String hookName();

    /** The mod ids any one of which means the mod is installed. */
    protected abstract List<String> modIds();

    /** Looks up every class and member the hook will use. Throws when one is not there. */
    protected abstract void resolve() throws ReflectiveOperationException;

    /** Whether the mod is installed at all, resolved or not. */
    public final boolean installed() {
        for (String modId : modIds()) {
            if (Services.PLATFORM.isModLoaded(modId)) {
                return true;
            }
        }
        return false;
    }

    /** Installed and every name resolved. Cheap after the first call, so ask it freely. */
    public final boolean ready() {
        if (state == State.BROKEN || !installed()) {
            return false;
        }
        if (state == State.UNTRIED) {
            try {
                resolve();
                state = State.READY;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                broken(e);
            }
        }
        return state == State.READY;
    }

    /**
     * Runs one use of the hook. A missing member or a class that is not what it was switches the
     * hook off; anything else is logged at debug and answered with {@code fallback}.
     */
    protected final <T> T call(Callable<T> body, T fallback) {
        if (!ready()) {
            return fallback;
        }
        try {
            return body.call();
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            broken(e);
            return fallback;
        } catch (Exception e) {
            Constants.LOG.debug("{} could not be used this time", hookName(), e);
            return fallback;
        }
    }

    private void broken(Throwable cause) {
        state = State.BROKEN;
        Constants.LOG.warn("{} is installed but no longer looks the way Lune expects, so Lune "
                + "stops using it for this session: {}", hookName(), cause.toString());
    }

    /** A class by its binary name, through the loader that holds every mod on all three loaders. */
    protected static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ModHook.class.getClassLoader());
    }
}
