package com.etka.lune.task;

/** Ten restore-button presses within five seconds reveal the secret tasks. */
public final class SecretTaskUnlock {
    public static final int PRESSES = 10;
    public static final long WINDOW_NANOS = 5_000_000_000L;
    private int count;
    private long first;

    public boolean press(long now) {
        if (count == 0 || now < first || now - first > WINDOW_NANOS) {
            first = now;
            count = 0;
        }
        if (++count < PRESSES) return false;
        count = 0;
        return true;
    }
}
