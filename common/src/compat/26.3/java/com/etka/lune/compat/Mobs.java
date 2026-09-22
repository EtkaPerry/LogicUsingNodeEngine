package com.etka.lune.compat;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/**
 * The entity types Lune knows by name, as they are spelled on Minecraft 26.3.
 *
 * <p>26.2 moved the constants off {@code EntityType} onto a new {@code EntityTypes} class, so the rest
 * of Lune reads {@code Mobs.ZOMBIE} and only this file knows where the constant lives. Add a mob here
 * and in each sibling copy. This file exists once per folder under {@code common/src/compat}; the build compiles the one
 * named by {@code compat_variant} in the target's gradle/versions file. Keep the copies in step.</p>
 */
public final class Mobs {

    public static final EntityType<?> ARMADILLO = EntityTypes.ARMADILLO;
    public static final EntityType<?> BEE = EntityTypes.BEE;
    public static final EntityType<?> BLAZE = EntityTypes.BLAZE;
    public static final EntityType<?> BOGGED = EntityTypes.BOGGED;
    public static final EntityType<?> BREEZE = EntityTypes.BREEZE;
    public static final EntityType<?> CAMEL = EntityTypes.CAMEL;
    public static final EntityType<?> CAT = EntityTypes.CAT;
    public static final EntityType<?> CAVE_SPIDER = EntityTypes.CAVE_SPIDER;
    public static final EntityType<?> CHICKEN = EntityTypes.CHICKEN;
    public static final EntityType<?> COD = EntityTypes.COD;
    public static final EntityType<?> COW = EntityTypes.COW;
    public static final EntityType<?> CREEPER = EntityTypes.CREEPER;
    public static final EntityType<?> DONKEY = EntityTypes.DONKEY;
    public static final EntityType<?> DROWNED = EntityTypes.DROWNED;
    public static final EntityType<?> ELDER_GUARDIAN = EntityTypes.ELDER_GUARDIAN;
    public static final EntityType<?> ENDERMAN = EntityTypes.ENDERMAN;
    public static final EntityType<?> ENDERMITE = EntityTypes.ENDERMITE;
    public static final EntityType<?> EVOKER = EntityTypes.EVOKER;
    public static final EntityType<?> FOX = EntityTypes.FOX;
    public static final EntityType<?> GHAST = EntityTypes.GHAST;
    public static final EntityType<?> GOAT = EntityTypes.GOAT;
    public static final EntityType<?> GUARDIAN = EntityTypes.GUARDIAN;
    public static final EntityType<?> HOGLIN = EntityTypes.HOGLIN;
    public static final EntityType<?> HORSE = EntityTypes.HORSE;
    public static final EntityType<?> HUSK = EntityTypes.HUSK;
    public static final EntityType<?> IRON_GOLEM = EntityTypes.IRON_GOLEM;
    public static final EntityType<?> MAGMA_CUBE = EntityTypes.MAGMA_CUBE;
    public static final EntityType<?> MULE = EntityTypes.MULE;
    public static final EntityType<?> PHANTOM = EntityTypes.PHANTOM;
    public static final EntityType<?> PIG = EntityTypes.PIG;
    public static final EntityType<?> PIGLIN = EntityTypes.PIGLIN;
    public static final EntityType<?> PIGLIN_BRUTE = EntityTypes.PIGLIN_BRUTE;
    public static final EntityType<?> PILLAGER = EntityTypes.PILLAGER;
    public static final EntityType<?> RABBIT = EntityTypes.RABBIT;
    public static final EntityType<?> RAVAGER = EntityTypes.RAVAGER;
    public static final EntityType<?> SALMON = EntityTypes.SALMON;
    public static final EntityType<?> SHEEP = EntityTypes.SHEEP;
    public static final EntityType<?> SHULKER = EntityTypes.SHULKER;
    public static final EntityType<?> SILVERFISH = EntityTypes.SILVERFISH;
    public static final EntityType<?> SKELETON = EntityTypes.SKELETON;
    public static final EntityType<?> SLIME = EntityTypes.SLIME;
    public static final EntityType<?> SPIDER = EntityTypes.SPIDER;
    public static final EntityType<?> STRAY = EntityTypes.STRAY;
    public static final EntityType<?> TURTLE = EntityTypes.TURTLE;
    public static final EntityType<?> VEX = EntityTypes.VEX;
    public static final EntityType<?> VILLAGER = EntityTypes.VILLAGER;
    public static final EntityType<?> VINDICATOR = EntityTypes.VINDICATOR;
    public static final EntityType<?> WARDEN = EntityTypes.WARDEN;
    public static final EntityType<?> WITCH = EntityTypes.WITCH;
    public static final EntityType<?> WITHER_SKELETON = EntityTypes.WITHER_SKELETON;
    public static final EntityType<?> WOLF = EntityTypes.WOLF;
    public static final EntityType<?> ZOGLIN = EntityTypes.ZOGLIN;
    public static final EntityType<?> ZOMBIE = EntityTypes.ZOMBIE;
    public static final EntityType<?> ZOMBIE_VILLAGER = EntityTypes.ZOMBIE_VILLAGER;
    public static final EntityType<?> ZOMBIFIED_PIGLIN = EntityTypes.ZOMBIFIED_PIGLIN;

    private Mobs() {}
}
