package com.etka.lune.bot.task;

/**
 * Small, testable rules for the food phase's surface prospecting.
 *
 * <p>A surface search may choose a new clearing, but it should not turn a food check into a
 * mountain expedition. Keeping this rule outside {@link SpeedrunTask} makes it explicit that the
 * settlement/shipwreck search uses the same travel constraint as its Nether hoglin fallback.
 */
final class FoodSearchPolicy {

    /** A normal food prospect can cross a hill, but not commit to a cliff or mountain climb. */
    static final int MAX_SURFACE_ELEVATION_DELTA = 8;

    private FoodSearchPolicy() {}

    static boolean staysOnSurfaceBand(int originY, int targetY) {
        return Math.abs(targetY - originY) <= MAX_SURFACE_ELEVATION_DELTA;
    }

    /** Prefer a short, level clearing when several loaded surface columns are available. */
    static int surfaceTargetScore(int originY, int targetY, int horizontalDistance) {
        return Math.abs(targetY - originY) * 12 + Math.max(0, horizontalDistance);
    }

    /** Only the Nether starts with a mob fallback; overworld food comes from standing sources. */
    static boolean shouldStartWithAnimalFallback(boolean nether) {
        return nether;
    }

    /** Surface-only overworld sources need an explicit exit when the previous task ended underground. */
    static boolean shouldPrepareSurface(boolean overworld, boolean alreadyOnSurface) {
        return overworld && !alreadyOnSurface;
    }

    /** Re-arm the short search budget when a normal roam has consumed its hunger reserve. */
    static boolean shouldShortenSourceSearch(boolean emergency, boolean overworld,
            int foodLevel, int reserve) {
        return !emergency && overworld && foodLevel <= reserve;
    }

    /**
     * Overworld food is an opportunity, not a destination. A route may take a source already in
     * view, but an empty sightline must never start a separate food expedition.
     */
    static boolean shouldTakeLocalOpportunity(boolean sourceVisible, boolean alreadyCheckedHere) {
        return sourceVisible && !alreadyCheckedHere;
    }

    /**
     * An empty Overworld sightline is not permission to leave the current route. Village, wreck,
     * and animal work is entered only when its source is already visible; the Nether has its own
     * bounded hoglin fallback and does not use the Overworld ROAM state.
     */
    static boolean allowSourceRoam(boolean emergency) {
        return false;
    }

    /** Keep an emergency search alive long enough to reach a nearby source, but not a long trek. */
    static int maxSourceRoams(boolean emergency) {
        return emergency ? 3 : 8;
    }

    static int sourceRoamMinDistance(boolean emergency) {
        return emergency ? 24 : 48;
    }

    static int sourceRoamMaxDistance(boolean emergency) {
        return emergency ? 48 : 96;
    }

    /**
     * Every third source sweep should bias toward a shoreline: it is where a player is most likely
     * to notice a shipwreck, while the intervening sweeps still favour open food-friendly land.
     */
    static boolean shouldProspectShoreline(int completedRoams) {
        return completedRoams > 0 && completedRoams % 3 == 0;
    }

    /** A structure search is worthwhile only when the current view contains a real shoreline clue. */
    static boolean shouldProbeVisibleShipwreck(boolean visibleClue, boolean checkedThisArea) {
        return visibleClue && !checkedThisArea;
    }

    /**
     * A wreck can show only the flat top of its hull above the waterline. The old two-counter
     * heuristic was too permissive: a coastal village house also has several planks and logs. A
     * human-like prospect therefore requires the stronger combination of a waterline hull, a
     * distinctive deck piece, and no visible village masonry in the same small cluster.
     */
    static boolean looksLikeVisibleShipwreck(int visiblePlanks, int visibleSupports,
            int waterlinePlanks, int deckFeatures, int visibleMasonry) {
        return visiblePlanks >= 3
                && visibleSupports >= 1
                && waterlinePlanks >= 2
                && deckFeatures >= 1
                && visibleMasonry == 0;
    }

}
