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
 *
 * <p>{@link #DIGGING}, {@link #FARMING}, {@link #CRAFTING}, {@link #SMELTING} and
 * {@link #COLLECTING} are the job in hand while she is at the work itself - breaking the block,
 * cutting the crop, standing at the furnace - rather than walking to it.</p>
 */
public enum StatusSignal {

    NONE,
    FARMING,
    SMELTING,
    CRAFTING,
    DIGGING,
    COLLECTING,
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
     *
     * <p>The five jobs sit under even the walking, for the same reason: the job is the outer status
     * and the walk is the inner one, and whatever she is doing this tick is the inner one. A tree
     * felled from the far side of a clearing reads as travel until she arrives, then as digging.
     * Among the jobs themselves the one done inside another comes first - collecting a drop happens
     * inside digging, digging inside crafting a tool, crafting a furnace inside smelting.</p>
     */
    public int priority() {
        return switch (this) {
            case DEAD -> 23;
            case SUCCESS -> 22;
            case DANGER -> 21;
            case FIGHT -> 20;
            case FLEE -> 19;
            case HURT -> 18;
            case RECOVERING -> 17;
            case INVENTORY_FULL -> 16;
            case MISSING_MATERIALS -> 15;
            case BLOCKED -> 14;
            case SEARCH -> 13;
            case LOADING -> 12;
            case WAITING -> 11;
            case FRAMING -> 10;
            case STAIRS -> 9;
            case PILLARING -> 8;
            case BRIDGING -> 7;
            case TRAVEL -> 6;
            case COLLECTING -> 5;
            case DIGGING -> 4;
            case CRAFTING -> 3;
            case SMELTING -> 2;
            case FARMING -> 1;
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
