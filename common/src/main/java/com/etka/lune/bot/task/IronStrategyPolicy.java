package com.etka.lune.bot.task;

/**
 * Pure rules for how the iron phase should look for iron; kept testable without Minecraft.
 *
 * <p>Walking the surface scanning for exposed ore is the worst of the three options and was the
 * default. A measured run spent 10,134 ticks in this phase - 84% of the whole run - and 3,230 of
 * them on routes that reported "previous route blocked; trying another way". Surface ore is rare,
 * the terrain fights every heading, and nothing about wandering gets better with time.</p>
 *
 * <p>The two things a player does instead both start from where they are standing:</p>
 * <ul>
 *   <li><b>On a coast</b>, get in a boat. The sea is the one direction with no terrain to fight,
 *       and it leads to shipwrecks - whose chests hold iron nobody had to mine. A village is the
 *       exception: it already has a smithy and hay, so leaving it for open water is a downgrade.</li>
 *   <li><b>Inland</b>, go down a cave. A big opening is somebody else's excavation: exposed ore in
 *       open air, no staircase to dig, and it reaches ore depth far faster than a bounded stair.</li>
 * </ul>
 *
 * <p>Both are bounded and both are abandoned on failure, falling back to the old surface search
 * rather than replacing it - the point is to try the good options first, not to forbid the bad one.</p>
 */
final class IronStrategyPolicy {

    /** What to do next when the phase needs a new place to look. */
    enum Strategy {
        /** Go to a village, wreck or portal already seen; somebody else did the mining. */
        STRUCTURE,
        /** Take the sea and look for wrecks; the coast is the reason. */
        SAIL,
        /** Go down an opening that already exists. */
        DIVE,
        /** Nothing better applies: fall back to the surface scout. */
        SCOUT
    }

    /**
     * Failed surface scouts tolerated before a better option is worth the setup cost. One failure
     * can be a single awkward hill; two in a row is the terrain telling you something.
     */
    static final int SCOUTS_BEFORE_SWITCHING = 2;

    private IronStrategyPolicy() {}

    /**
     * Chooses how to look for iron next.
     *
     * @param failedScouts  surface scouts that came back with nothing
     * @param coastal       open water within reach of a launch
     * @param inVillage     standing in a village, which is worth more than the open sea
     * @param caveInReach   a visible opening big enough to walk into
     * @param sailedAlready whether this run has already spent its one crossing
     */
    static Strategy choose(int failedScouts, boolean coastal, boolean inVillage,
                           boolean caveInReach, boolean sailedAlready, boolean structureKnown) {
        return choose(failedScouts, coastal, inVillage, caveInReach, sailedAlready,
                structureKnown, true);
    }

    /**
     * @param armed whether the run has a weapon; an unarmed structure visit is not an opportunity
     */
    static Strategy choose(int failedScouts, boolean coastal, boolean inVillage,
                           boolean caveInReach, boolean sailedAlready, boolean structureKnown,
                           boolean armed) {
        // Somebody else's iron, already smelted, sitting in a chest. Nothing the bot can do with a
        // pickaxe competes with that, so a known structure outranks every digging option.
        //
        // Only once there is something to fight with. A wreck is guarded by drowned and a village
        // by whatever wandered in overnight, and arriving bare-handed in the first minute of a run
        // is a trip that ends in damage and no iron - the structure is still there afterwards, so
        // the visit is worth postponing rather than abandoning.
        if (structureKnown && armed) {
            return Strategy.STRUCTURE;
        }
        // On a coast the sea is the way to more structures - wrecks are out there and the water is
        // the one direction with no terrain to fight. A village is the exception: it is already a
        // better structure than anything the sea leads to.
        if (coastal && !inVillage && !sailedAlready) {
            return Strategy.SAIL;
        }
        if (failedScouts < SCOUTS_BEFORE_SWITCHING) {
            return Strategy.SCOUT;
        }
        // Inland, with the surface search spent: an existing opening beats digging a new one. This
        // sits below looking for structures rather than above it - going underground is what the
        // run does when there is nothing built to raid, not its opening move.
        if (caveInReach) {
            return Strategy.DIVE;
        }
        return Strategy.SCOUT;
    }

    /** A village is worth staying in: the smithy is iron and the farm is food, both already here. */
    static boolean worthStayingForVillage(boolean inVillage, boolean smithyKnown) {
        return inVillage || smithyKnown;
    }

    /**
     * Searches for something built that must come back empty before a shaft is sunk.
     *
     * <p>Higher than {@link #SCOUTS_BEFORE_SWITCHING} on purpose. That threshold decides when to
     * change *how* to look; this one decides whether to give up looking at all and start digging,
     * which is the most expensive thing the phase can do and the thing it should do last.
     */
    static final int SEARCHES_BEFORE_DIGGING = 4;

    /**
     * Whether the run has earned the right to dig for its iron.
     *
     * <p>The shaft was the phase's fallback in name only: any tick where no structure had been
     * noticed and no ore was visible went straight to it, so a run that had never gone looking for
     * a village or a wreck would be underground within a minute. Digging is the worst option on
     * every measure - slowest, most dangerous, and it competes with structures that hold iron
     * somebody has already mined and smelted. It waits until looking has actually been tried.
     */
    static boolean mayDigForIron(int searchesDone, boolean structureKnown, boolean caveInReach) {
        if (structureKnown || caveInReach) {
            return false;
        }
        return searchesDone >= SEARCHES_BEFORE_DIGGING;
    }
}
