package com.etka.lune.compat;

import net.minecraft.world.attribute.BedRule;

/**
 * What a dimension's bed rule says about sleeping, on Minecraft 26.3.
 *
 * <p>Up to 26.2 the rule had one flag, {@code explodes}; 26.3 split it into {@code destroyOnUse}
 * and {@code destroyOnLeave}, and the first of those is the old meaning. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Beds {

    private Beds() {}

    /** True where using a bed destroys it, as in the Nether and the End. */
    public static boolean explodes(BedRule rule) {
        return rule.destroyOnUse();
    }
}
