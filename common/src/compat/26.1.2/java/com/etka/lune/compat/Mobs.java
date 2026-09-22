package com.etka.lune.compat;

import net.minecraft.world.entity.EntityType;

/**
 * The entity types Lune knows by name, as they are spelled on Minecraft 26.1.2.
 *
 * <p>26.2 moved the constants off {@code EntityType} onto a new {@code EntityTypes} class, so the rest
 * of Lune reads {@code Mobs.ZOMBIE} and only this file knows where the constant lives. Add a mob here
 * and in each sibling copy. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Mobs {

    public static final EntityType<?> ARMADILLO = EntityType.ARMADILLO;
    public static final EntityType<?> BEE = EntityType.BEE;
    public static final EntityType<?> BLAZE = EntityType.BLAZE;
    public static final EntityType<?> BOGGED = EntityType.BOGGED;
    public static final EntityType<?> BREEZE = EntityType.BREEZE;
    public static final EntityType<?> CAMEL = EntityType.CAMEL;
    public static final EntityType<?> CAT = EntityType.CAT;
    public static final EntityType<?> CAVE_SPIDER = EntityType.CAVE_SPIDER;
    public static final EntityType<?> CHICKEN = EntityType.CHICKEN;
    public static final EntityType<?> COD = EntityType.COD;
    public static final EntityType<?> COW = EntityType.COW;
    public static final EntityType<?> CREEPER = EntityType.CREEPER;
    public static final EntityType<?> DONKEY = EntityType.DONKEY;
    public static final EntityType<?> DROWNED = EntityType.DROWNED;
    public static final EntityType<?> ELDER_GUARDIAN = EntityType.ELDER_GUARDIAN;
    public static final EntityType<?> ENDERMAN = EntityType.ENDERMAN;
    public static final EntityType<?> ENDERMITE = EntityType.ENDERMITE;
    public static final EntityType<?> EVOKER = EntityType.EVOKER;
    public static final EntityType<?> FOX = EntityType.FOX;
    public static final EntityType<?> GHAST = EntityType.GHAST;
    public static final EntityType<?> GOAT = EntityType.GOAT;
    public static final EntityType<?> GUARDIAN = EntityType.GUARDIAN;
    public static final EntityType<?> HOGLIN = EntityType.HOGLIN;
    public static final EntityType<?> HORSE = EntityType.HORSE;
    public static final EntityType<?> HUSK = EntityType.HUSK;
    public static final EntityType<?> IRON_GOLEM = EntityType.IRON_GOLEM;
    public static final EntityType<?> MAGMA_CUBE = EntityType.MAGMA_CUBE;
    public static final EntityType<?> MULE = EntityType.MULE;
    public static final EntityType<?> PHANTOM = EntityType.PHANTOM;
    public static final EntityType<?> PIG = EntityType.PIG;
    public static final EntityType<?> PIGLIN = EntityType.PIGLIN;
    public static final EntityType<?> PIGLIN_BRUTE = EntityType.PIGLIN_BRUTE;
    public static final EntityType<?> PILLAGER = EntityType.PILLAGER;
    public static final EntityType<?> RABBIT = EntityType.RABBIT;
    public static final EntityType<?> RAVAGER = EntityType.RAVAGER;
    public static final EntityType<?> SALMON = EntityType.SALMON;
    public static final EntityType<?> SHEEP = EntityType.SHEEP;
    public static final EntityType<?> SHULKER = EntityType.SHULKER;
    public static final EntityType<?> SILVERFISH = EntityType.SILVERFISH;
    public static final EntityType<?> SKELETON = EntityType.SKELETON;
    public static final EntityType<?> SLIME = EntityType.SLIME;
    public static final EntityType<?> SPIDER = EntityType.SPIDER;
    public static final EntityType<?> STRAY = EntityType.STRAY;
    public static final EntityType<?> TURTLE = EntityType.TURTLE;
    public static final EntityType<?> VEX = EntityType.VEX;
    public static final EntityType<?> VILLAGER = EntityType.VILLAGER;
    public static final EntityType<?> VINDICATOR = EntityType.VINDICATOR;
    public static final EntityType<?> WARDEN = EntityType.WARDEN;
    public static final EntityType<?> WITCH = EntityType.WITCH;
    public static final EntityType<?> WITHER_SKELETON = EntityType.WITHER_SKELETON;
    public static final EntityType<?> WOLF = EntityType.WOLF;
    public static final EntityType<?> ZOGLIN = EntityType.ZOGLIN;
    public static final EntityType<?> ZOMBIE = EntityType.ZOMBIE;
    public static final EntityType<?> ZOMBIE_VILLAGER = EntityType.ZOMBIE_VILLAGER;
    public static final EntityType<?> ZOMBIFIED_PIGLIN = EntityType.ZOMBIFIED_PIGLIN;

    private Mobs() {}
}
