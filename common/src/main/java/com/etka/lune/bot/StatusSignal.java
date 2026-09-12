package com.etka.lune.bot;

/**
 * What a piece of task status <em>means</em>, as opposed to what it says.
 *
 * <p>Lune's face used to be worked out by searching her own status text for English words - "lava",
 * "inventory full", "no route". That reads fine until the text is translated, at which point every
 * mood silently becomes {@link #NONE} and the mascot smiles through a lava bath. The meaning is
 * therefore declared next to the line rather than recovered from it, and it survives translation
 * because it was never written in English in the first place.</p>
 *
 * <p>{@link #DANGER} is the world: lava, water, air, a fall. A mob is {@link #FIGHT} when she is
 * facing it and {@link #FLEE} when she is getting away from it, and her own body is {@link #HURT},
 * {@link #RECOVERING} or {@link #DEAD}.</p>
 */
public enum StatusSignal {

    NONE,
    TRAVEL,
    BRIDGING,
    PILLARING,
    STAIRS,
    FRAMING,
    WAITING,
    LOADING,
    SEARCH,
    BLOCKED,
    MISSING_MATERIALS,
    INVENTORY_FULL,
    RECOVERING,
    HURT,
    FLEE,
    FIGHT,
    DANGER,
    SUCCESS,
    DEAD;

    /**
     * Which signal wins when a status is built out of several.
     *
     * <p>The older signals keep the order the original keyword search happened to test in - success
     * still tops danger - so a composed status that used to come out DANGER because a child
     * mentioned lava still does. The signals split out of danger sit just under it in the order a
     * player would want to hear them: being dead outranks everything, then the world, then the mob,
     * then her own health. The ones that merely say which job is in hand sit at the bottom, under
     * every kind of trouble, so a bridge that cannot be built reads as a blockage and not as
     * bridging.</p>
     */
    public int priority() {
        return switch (this) {
            case DEAD -> 18;
            case SUCCESS -> 17;
            case DANGER -> 16;
            case FIGHT -> 15;
            case FLEE -> 14;
            case HURT -> 13;
            case RECOVERING -> 12;
            case INVENTORY_FULL -> 11;
            case MISSING_MATERIALS -> 10;
            case BLOCKED -> 9;
            case SEARCH -> 8;
            case LOADING -> 7;
            case WAITING -> 6;
            case FRAMING -> 5;
            case STAIRS -> 4;
            case PILLARING -> 3;
            case BRIDGING -> 2;
            case TRAVEL -> 1;
            case NONE -> 0;
        };
    }

    /** The stronger of two signals, for a parent task wrapping a child's status. */
    public StatusSignal or(StatusSignal other) {
        if (other == null) {
            return this;
        }
        return other.priority() > priority() ? other : this;
    }
}
