package com.etka.lune.bot;

import com.etka.lune.bot.input.BotInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Turns one driving tick into run counters: how far the bot moved, what it lost health to, and what
 * arrived in the bag.
 *
 * <p>These are all things Minecraft already counts and the client never learns. Its
 * {@code StatsCounter} is only sent to the client when the vanilla statistics screen asks the
 * server for it, so reading distance or damage from there reports whatever was true the last time
 * a human opened that screen - usually zero. Sampling the player is the only way the dashboard can
 * describe a run that is still going.</p>
 *
 * <p>Everything is measured against the previous driving tick, so a paused or idle bot contributes
 * nothing, and the numbers describe the bot's work rather than the session.</p>
 */
final class RunSampler {

    /**
     * A single tick cannot legitimately move a player ten blocks. Anything larger is a portal, a
     * respawn, or the server correcting the position, none of which are travel.
     */
    private static final long MAX_STEP_CM = 1000L;
    /** Health at or below this share of the maximum is a close call rather than ordinary damage. */
    private static final float CLOSE_CALL_SHARE = 0.3F;

    private Vec3 lastPosition;
    private float lastHealth;
    private int lastExperience;
    private int lastItems;
    private boolean started;
    private boolean wasJumping;
    private boolean wasLowHealth;
    private boolean wasDead;

    /** Forgets the previous run so its last tick cannot be differenced against this run's first. */
    void reset() {
        lastPosition = null;
        started = false;
        wasJumping = false;
        wasLowHealth = false;
        wasDead = false;
    }

    /** Samples one tick of a driving bot. {@code anchor} is where the run began, if known. */
    void tick(LocalPlayer player, BotInput input, DebugInfo debug, BlockPos anchor) {
        if (player == null || debug == null) {
            return;
        }
        Vec3 position = player.position();
        float health = player.getHealth();
        int experience = player.totalExperience;
        int items = countItems(player);

        if (started) {
            sampleMovement(position, player, input, debug);
            sampleHealth(health, player, debug);
            if (experience > lastExperience) {
                debug.count("xp_gained", experience - lastExperience);
            }
            if (items > lastItems) {
                debug.count("items_gained", items - lastItems);
            }
        }

        if (anchor != null) {
            debug.peak("home_cm", Math.round(Math.sqrt(anchor.distToCenterSqr(position)) * 100.0));
        }

        lastPosition = position;
        lastHealth = health;
        lastExperience = experience;
        lastItems = items;
        started = true;
    }

    private void sampleMovement(Vec3 position, LocalPlayer player, BotInput input, DebugInfo debug) {
        if (lastPosition == null) {
            return;
        }
        double dy = position.y - lastPosition.y;
        long moved = Math.round(position.distanceTo(lastPosition) * 100.0);
        if (moved > 0L && moved < MAX_STEP_CM) {
            debug.count("distance_cm", moved);
            if (player.isSprinting()) {
                debug.count("sprint_cm", moved);
            }
            if (player.isInWater()) {
                debug.count("swim_cm", moved);
            }
            debug.count(dy >= 0.0 ? "climb_cm" : "descend_cm", Math.round(Math.abs(dy) * 100.0));
        }
        // The key alone is not a jump: tasks hold it down against walls and in water, where it
        // never leaves the ground. Count the press that actually launches.
        boolean jumping = input != null && input.jump;
        if (jumping && !wasJumping && player.onGround()) {
            debug.count("jumps");
        }
        wasJumping = jumping;
    }

    private void sampleHealth(float health, LocalPlayer player, DebugInfo debug) {
        if (health < lastHealth) {
            long lost = Math.round((lastHealth - health) * 10.0);
            debug.count("health_lost_tenths", lost);
            debug.peak("hit_tenths", lost);
        }
        boolean low = health > 0.0F && health <= player.getMaxHealth() * CLOSE_CALL_SHARE;
        if (low && !wasLowHealth) {
            debug.count("close_calls");
        }
        wasLowHealth = low;

        boolean dead = !player.isAlive();
        if (dead && !wasDead) {
            debug.count("deaths");
        }
        wasDead = dead;
    }

    /** Total item count carried, which grows by exactly what the bot picked up or crafted. */
    private static int countItems(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
