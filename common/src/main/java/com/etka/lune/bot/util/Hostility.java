package com.etka.lune.bot.util;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.polarbear.PolarBear;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;

/**
 * Whether a mob means the player harm: what it does on sight, and who has already hit them.
 *
 * <p>Vanilla's {@link Enemy} marker says what a mob is, not what it will do to this player, and
 * three of the Nether's mobs carry it while leaving the player alone. A grown piglin attacks nobody
 * wearing a piece of golden armour; a baby piglin attacks nobody at all; a zombified piglin attacks
 * only once it, or one near it, has been hit - and then every one of them in reach comes. Reading
 * the marker as the answer is how Self Preservation came to swing at a piglin that was minding its
 * own business beside a player in gold, and a piglin that is hit calls in every piglin near it.</p>
 *
 * <p>Such a mob is a threat the moment it attacks, which is what {@link LastHit} is for: a player
 * who has been hit knows it, whatever they were wearing. One answer for every card that asks the
 * question - Self Preservation, the staircase deciding whether the cave it opened is safe to enter,
 * the speedrun clearing a site before it loots, and the Main tab's count of enemies in sight.</p>
 */
public final class Hostility {

    /** The enemies whose temper depends on more than what they are. */
    enum Kind {
        /** Attacks on sight whatever the player wears: a zombie, a hoglin, a piglin brute. */
        OTHER,
        /** Leaves a player in gold alone, and is harmless as a baby. */
        PIGLIN,
        /** Leaves everybody alone until provoked. */
        ZOMBIFIED_PIGLIN
    }

    private Hostility() {}

    /** Whether this mob attacks the player on sight, as they are now - in what they are wearing. */
    public static boolean attacksOnSight(Player player, Entity entity) {
        return entity instanceof Enemy && !leavesAlone(player, entity);
    }

    /** An enemy that leaves this player alone until it is provoked. */
    public static boolean leavesAlone(Player player, Entity entity) {
        Kind kind = kind(entity.getClass());
        if (kind == Kind.OTHER) {
            return false;
        }
        boolean baby = entity instanceof LivingEntity living && living.isBaby();
        // Asked of the game rather than of a list of golden items: the tag is what vanilla reads,
        // and a loader that lets an item calm piglins its own way answers through the same call.
        return leavesAlone(kind, baby, kind == Kind.PIGLIN && PiglinAi.isWearingSafeArmor(player));
    }

    /**
     * Whether a hit from this mob is worth answering, whatever it was doing before: any enemy, the
     * ones that were leaving the player alone included, and a polar bear.
     *
     * <p>A polar bear carries no enemy marker and ignores a player who leaves it alone, so it is no
     * threat on sight. The bot once walled itself in beside one that was minding its own business
     * and stayed there, because the bear never stopped being a threat and so the escape never
     * finished. It becomes one the moment it attacks.</p>
     */
    public static boolean dangerousOnceProvoked(Entity entity) {
        return entity instanceof Enemy || entity instanceof PolarBear;
    }

    /**
     * Which of the enemies this is, by class rather than by type, so a mod's piglin that keeps
     * vanilla's brain is read the same way.
     *
     * <p>A brute is an {@code AbstractPiglin} and not a {@code Piglin}. That is the line vanilla's
     * own sensor draws too: gold buys nothing from a brute.</p>
     */
    static Kind kind(Class<?> type) {
        if (ZombifiedPiglin.class.isAssignableFrom(type)) {
            return Kind.ZOMBIFIED_PIGLIN;
        }
        if (Piglin.class.isAssignableFrom(type)) {
            return Kind.PIGLIN;
        }
        return Kind.OTHER;
    }

    /** The rule itself, on facts a test can state without a world. */
    static boolean leavesAlone(Kind kind, boolean baby, boolean wearingGold) {
        return switch (kind) {
            case ZOMBIFIED_PIGLIN -> true;
            // Only a grown piglin ever starts a fight. A baby that is hit runs, and calls the
            // grown ones in.
            case PIGLIN -> baby || wearingGold;
            case OTHER -> false;
        };
    }

    /**
     * The mob that last hurt the player, and when.
     *
     * <p>{@code getLastHurtByMob} cannot be asked on this side. Vanilla fills it in while the server
     * applies the damage, on the server's own copy of the player; the client's copy only ever gets
     * the damage event, so the field stays empty and every hit read as nobody's. What the event does
     * carry is the source, attacker included - the archer for an arrow, the Ghast for a fireball -
     * and the game keeps that for only forty ticks. This keeps the attacker for as long as the
     * caller asks, and a fall or a burn in between does not wipe it, the same as the server's
     * record.</p>
     */
    public static final class LastHit {
        private DamageSource seen;
        private int attackerId = -1;
        private long at;

        /** Reads the game's record of the last hit. Asked every tick; a hit stays readable for forty. */
        public void observe(Player player) {
            DamageSource source = player.getLastDamageSource();
            if (source == null || source == seen) {
                return;
            }
            seen = source;
            if (source.getEntity() instanceof LivingEntity attacker) {
                attackerId = attacker.getId();
                at = player.level().getGameTime();
            }
        }

        /** Whether this mob hurt the player within the last {@code ticks}. */
        public boolean by(Entity entity, Player player, int ticks) {
            return entity != null && attackerId >= 0 && entity.getId() == attackerId
                    && player.level().getGameTime() - at < ticks;
        }
    }
}
