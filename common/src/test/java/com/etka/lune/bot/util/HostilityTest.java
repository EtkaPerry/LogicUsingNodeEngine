package com.etka.lune.bot.util;

import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which enemies leave the player alone, and what it takes.
 *
 * <p>Getting this wrong is a fight the player never asked for: a swing at a piglin that was ignoring
 * a player in gold calls in every piglin near it, and a swing at a zombified piglin the whole group.
 */
class HostilityTest {

    @Test
    void goldKeepsAGrownPiglinOff() {
        assertTrue(Hostility.leavesAlone(Hostility.Kind.PIGLIN, false, true));
    }

    @Test
    void withoutGoldAGrownPiglinAttacks() {
        assertFalse(Hostility.leavesAlone(Hostility.Kind.PIGLIN, false, false));
    }

    @Test
    void aBabyPiglinNeverStartsAFight() {
        assertTrue(Hostility.leavesAlone(Hostility.Kind.PIGLIN, true, false));
    }

    @Test
    void aZombifiedPiglinWaitsToBeProvokedWhateverIsWorn() {
        assertTrue(Hostility.leavesAlone(Hostility.Kind.ZOMBIFIED_PIGLIN, false, false));
        assertTrue(Hostility.leavesAlone(Hostility.Kind.ZOMBIFIED_PIGLIN, true, true));
    }

    @Test
    void goldBuysNothingFromAnyOtherEnemy() {
        assertFalse(Hostility.leavesAlone(Hostility.Kind.OTHER, false, true));
        // A baby zombie is faster than the player and already coming.
        assertFalse(Hostility.leavesAlone(Hostility.Kind.OTHER, true, true));
    }

    @Test
    void aBruteIsNotAPiglin() {
        // Both are piglins to look at, and a brute ignores gold. Testing for AbstractPiglin
        // instead of Piglin would make a brute walk past a player in gold armour unanswered.
        assertEquals(Hostility.Kind.OTHER, Hostility.kind(PiglinBrute.class));
        assertEquals(Hostility.Kind.PIGLIN, Hostility.kind(Piglin.class));
    }

    @Test
    void aZombifiedPiglinIsNotAnOrdinaryZombie() {
        // It extends Zombie, so the order of the tests is what keeps the two apart.
        assertEquals(Hostility.Kind.ZOMBIFIED_PIGLIN, Hostility.kind(ZombifiedPiglin.class));
        assertEquals(Hostility.Kind.OTHER, Hostility.kind(Zombie.class));
        assertEquals(Hostility.Kind.OTHER, Hostility.kind(Hoglin.class));
    }
}
