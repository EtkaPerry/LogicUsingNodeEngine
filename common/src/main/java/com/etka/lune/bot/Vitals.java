package com.etka.lune.bot;

import java.util.Locale;

/**
 * Pure formatting for the player state a run journal needs; kept testable without Minecraft.
 *
 * <p>None of this is in {@link DebugInfo}, because the HUD draws the real hearts and hunger bar
 * right next to it. A journal read the next morning has no such luxury: without these numbers a
 * drowning and a creeper look identical afterwards - the trace simply stops.</p>
 */
public final class Vitals {

    private Vitals() {}

    public static String describe(float health, float maxHealth, int food, float saturation,
                                  int air, int maxAir, int armor, String lastDamage) {
        return "hp=" + round(health) + "/" + round(maxHealth)
                + ";food=" + food
                + ";sat=" + round(saturation)
                + ";air=" + Math.max(0, air) + "/" + Math.max(0, maxAir)
                + ";armor=" + armor
                // Health that drops with no attribution is a mystery every single time. The source
                // is the difference between "a zombie found us" and "we walked into our own lava".
                + ";dmg=" + (lastDamage == null || lastDamage.isBlank() ? "-" : lastDamage);
    }

    /**
     * Coarse vitals for the journal's change signature.
     *
     * <p>Health regenerates in fractions and air drops by one every tick underwater, so signing the
     * raw values would turn a fifteen-second dive into three hundred snapshot lines. Whole hearts
     * and tenths of air keep the shape of the damage - which is the part worth reading - at a
     * bounded cost.</p>
     */
    public static String signature(float health, int food, int air, int maxAir) {
        int airBucket = maxAir <= 0 ? 0 : Math.max(0, Math.min(air, maxAir)) * 10 / maxAir;
        return Math.round(health) + "|" + food + "|" + airBucket;
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
