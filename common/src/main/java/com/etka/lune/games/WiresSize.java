package com.etka.lune.games;

import java.util.Locale;

/** The boards Redstone Wires offers, smallest first. */
public enum WiresSize {
    SMALL(5, 5),
    MEDIUM(7, 7),
    LARGE(9, 9),
    HUGE(13, 11);

    private final int columns;
    private final int rows;

    WiresSize(int columns, int rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    /** What the records are kept under in the config. Never shown, and never translated. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** "7×7": two numbers, so it reads the same in every language and needs no key. */
    public String label() {
        return columns + "×" + rows;
    }
}
