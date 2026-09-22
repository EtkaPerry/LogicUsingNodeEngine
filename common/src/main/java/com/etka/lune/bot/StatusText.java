package com.etka.lune.bot;

import com.etka.lune.util.Lang;

/**
 * A task's one-line live detail, held as a key and its arguments rather than as finished words.
 *
 * <p>Keeping the key instead of the sentence is what lets the same status be read two ways. The
 * Main tab wants the player's language, so it asks for {@link #text()}. The mascot wants to know
 * whether to look worried, so it asks for {@link #signal()} - and gets an answer that does not
 * depend on which language the sentence came out in.</p>
 *
 * <p>Statuses nest. A task that walks somewhere as part of a larger job says "bridging - %s" and
 * hands in the child's own status; pass the child's {@code StatusText} as the argument and the
 * child's meaning comes with it, so a parent wrapping a task that is standing in lava is still
 * reported as danger.</p>
 */
public final class StatusText {

    // Declared before EMPTY, and it has to stay that way. Static initialisers run in the order
    // they are written, so an EMPTY built above this line is built while NO_ARGS is still null -
    // and every instance field is assigned from it. That is not a theoretical ordering point: it
    // left EMPTY.args null for the life of the process, and `status.set(child.status())` threw
    // NullPointerException on "other.args" for every child that had not overridden status(), which
    // Task.java answers with EMPTY. A Cov Walk run died on it 433 ticks in.
    private static final Object[] NO_ARGS = new Object[0];

    /** The status of a task that has not said anything yet. Shared, and refuses to be written to. */
    public static final StatusText EMPTY = new StatusText(true);

    private final boolean frozen;
    private String key = "";
    private Object[] args = NO_ARGS;

    public StatusText() {
        this(false);
    }

    private StatusText(boolean frozen) {
        this.frozen = frozen;
    }

    /** Says {@code key}, with any {@code %s} in it filled from {@code args} in order. */
    public StatusText set(String key, Object... args) {
        if (frozen) {
            return this;
        }
        this.key = key == null ? "" : key;
        this.args = args == null || args.length == 0 ? NO_ARGS : args.clone();
        return this;
    }

    /** Adopts another status wholesale, meaning and all - for {@code status = child.status()}. */
    public StatusText set(StatusText other) {
        if (frozen) {
            return this;
        }
        if (other == null) {
            return clear();
        }
        this.key = other.key;
        this.args = other.args.length == 0 ? NO_ARGS : other.args.clone();
        return this;
    }

    public StatusText clear() {
        if (frozen) {
            return this;
        }
        this.key = "";
        this.args = NO_ARGS;
        return this;
    }

    public boolean isBlank() {
        return key.isEmpty();
    }

    /** The translation key, for tests and for the run journals, which must not drift by language. */
    public String key() {
        return key;
    }

    /** The line as the player reads it. */
    public String text() {
        if (key.isEmpty()) {
            return "";
        }
        if (args.length == 0) {
            return Lang.get(key);
        }
        Object[] resolved = new Object[args.length];
        for (int index = 0; index < args.length; index++) {
            Object arg = args[index];
            resolved[index] = arg instanceof StatusText nested ? nested.text() : arg;
        }
        return Lang.get(key, resolved);
    }

    /** What this status means, including anything inherited from a nested status. */
    public StatusSignal signal() {
        StatusSignal signal = StatusKeys.signalOf(key);
        for (Object arg : args) {
            if (arg instanceof StatusText nested) {
                signal = signal.or(nested.signal());
            }
        }
        return signal;
    }

    @Override
    public String toString() {
        return text();
    }
}
