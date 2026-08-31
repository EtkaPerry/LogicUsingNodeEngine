package com.etka.lune.bot.task;

/**
 * Optional preparation and safety behaviour for {@link KillTask}.
 *
 * <p>The ordinary Kill task remains useful with no options at all. These settings add preparation
 * when the selected target needs it: fire resistance is considered for Blazes, shields are raised
 * against dangerous mobs, weapons can be selected or crafted, and Endermen can be fought from a
 * small shelter or inside a boat.</p>
 */
public record KillOptions(
        boolean useFireResistance,
        boolean useShield,
        boolean craftShield,
        EndermanSafety endermanSafety,
        WeaponPreference weapon,
        boolean craftWeapon
) {

    /** Source-compatible constructor for existing callers that only configure protection. */
    public KillOptions(boolean useFireResistance, boolean useShield, boolean craftShield,
                       EndermanSafety endermanSafety) {
        this(useFireResistance, useShield, craftShield, endermanSafety,
                WeaponPreference.AUTO, false);
    }

    public KillOptions {
        endermanSafety = endermanSafety == null ? EndermanSafety.DIRECT : endermanSafety;
        weapon = weapon == null ? WeaponPreference.AUTO : weapon;
    }

    /** Preserves the original generic KillTask behaviour for callers using the old constructor. */
    public static KillOptions basic() {
        return new KillOptions(false, false, false, EndermanSafety.DIRECT);
    }

    /** Sensible defaults for the task editor: automatic protection without forced crafting. */
    public static KillOptions taskDefaults() {
        return new KillOptions(true, true, false, EndermanSafety.AUTO,
                WeaponPreference.AUTO, false);
    }

    public enum EndermanSafety {
        /** Prefer a boat, then build a two-block shelter if that is not possible. */
        AUTO,
        /** Only use a boat trap; do not build. */
        BOAT,
        /** Build or use a two-block shelter; do not place a boat. */
        TWO_BLOCK_SHELTER,
        /** Fight the Enderman directly, with the normal KillTask movement. */
        DIRECT;

        public static EndermanSafety parse(String value) {
            if (value == null) {
                return AUTO;
            }
            return switch (value.trim().toLowerCase()) {
                case "boat" -> BOAT;
                case "two-block shelter", "two_block_shelter", "shelter" -> TWO_BLOCK_SHELTER;
                case "direct melee", "direct", "melee" -> DIRECT;
                default -> AUTO;
            };
        }
    }

    /** Which combat weapon should be held when the target is within attack range. */
    public enum WeaponPreference {
        /** Keep the old behaviour: equip any available weapon component. */
        AUTO,
        SWORD,
        AXE,
        BOW;

        public static WeaponPreference parse(String value) {
            if (value == null) {
                return AUTO;
            }
            return switch (value.trim().toLowerCase()) {
                case "sword" -> SWORD;
                case "axe" -> AXE;
                case "bow" -> BOW;
                default -> AUTO;
            };
        }
    }
}
