package com.etka.lune.bot.task;

import java.util.ArrayList;
import java.util.List;

/** Bounded strategy vocabulary for one concrete Self Preservation emergency. */
public final class SelfPreservationPolicy {

    public static final String DIRECT = "direct-recovery";
    public static final String RETREAT_FIRST = "retreat-first";
    public static final String COVER_FIRST = "cover-first";
    public static final String PILLAR_FIRST = "pillar-first";
    public static final String COUNTERATTACK_FIRST = "counterattack-first";
    public static final String SHELTER_FIRST = "shelter-first";
    public static final String WATER_CLUTCH = "water-clutch";
    public static final String CUSHION_CLUTCH = "cushion-clutch";
    public static final String BOAT_CLUTCH = "boat-clutch";

    private SelfPreservationPolicy() {}

    /** Only strategies valid for this hostile and carried resources are offered to learning. */
    public static List<String> monsterActions(boolean creeper, boolean enderman, boolean ranged,
                                              boolean canBuild, boolean canFight) {
        if (enderman) {
            return List.of(SHELTER_FIRST);
        }
        List<String> actions = new ArrayList<>();
        actions.add(RETREAT_FIRST);
        if (canBuild) {
            actions.add(COVER_FIRST);
            if (!creeper && !ranged) {
                actions.add(PILLAR_FIRST);
            }
        }
        if (canFight && !creeper && !ranged) {
            actions.add(COUNTERATTACK_FIRST);
        }
        return List.copyOf(actions);
    }

    /** A clutch method is offered only when it can physically work at the start of this fall. */
    public static List<String> fallActions(boolean water, boolean cushion,
                                           boolean boat, boolean boatHasTime) {
        List<String> actions = new ArrayList<>();
        if (water) actions.add(WATER_CLUTCH);
        if (cushion) actions.add(CUSHION_CLUTCH);
        if (boat && boatHasTime) actions.add(BOAT_CLUTCH);
        // Trying the boat is still the only possible recovery when nothing safer is carried.
        if (actions.isEmpty() && boat) actions.add(BOAT_CLUTCH);
        return actions.isEmpty() ? List.of(DIRECT) : List.copyOf(actions);
    }

    static ClutchPolicy.Method preferredClutch(String action) {
        return switch (action) {
            case WATER_CLUTCH -> ClutchPolicy.Method.WATER;
            case CUSHION_CLUTCH -> ClutchPolicy.Method.CUSHION;
            case BOAT_CLUTCH -> ClutchPolicy.Method.BOAT;
            default -> ClutchPolicy.Method.NONE;
        };
    }

    public static String distanceBucket(double distance) {
        if (distance <= 3.5) return "contact";
        if (distance <= 8.0) return "near";
        return "far";
    }

    public static String healthBucket(double health) {
        if (health <= 6.0) return "critical";
        if (health <= 14.0) return "hurt";
        return "healthy";
    }

    public static String fallBucket(double fallDistance) {
        if (fallDistance <= 8.0) return "short";
        if (fallDistance <= 24.0) return "deep";
        return "extreme";
    }
}
