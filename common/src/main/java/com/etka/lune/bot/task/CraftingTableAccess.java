package com.etka.lune.bot.task;

import com.etka.lune.bot.StatusText;
import com.etka.lune.bot.BotContext;
import com.etka.lune.bot.TaskStatus;
import com.etka.lune.bot.memory.CraftingTableMemory;
import com.etka.lune.bot.path.Goals;
import com.etka.lune.bot.util.BlockPlacer;
import com.etka.lune.bot.util.BlockScanner;
import com.etka.lune.bot.util.InventoryHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Getting a 3×3 grid open: find a table, or place one, walk to it, and click it.
 * <p>
 * Shared rather than owned by {@link CraftTask} because that is not the only card that needs a
 * table. The bot remembers tables it has placed or used and can walk back to one when no wood is
 * close, while staying willing to build a fresh one when that is cheaper than the hike - and the
 * reasoning about which is cheaper, which tables have turned out to be unreachable, and where a new
 * one can legally go is the same reasoning wherever the grid is wanted.
 */
final class CraftingTableAccess {

    /** Ticks to wait after a container action, so the server's reply lands before the next one. */
    private static final int ACTION_COOLDOWN = 5;
    /**
     * Default radius for looking for an existing crafting table before placing one.
     * <p>
     * Deliberately short. Since the bot stopped reclaiming its tables there is usually one standing
     * somewhere behind it, and walking back to it is only worth doing if it is genuinely underfoot.
     * A table is four planks; further than this and it is faster to make another one where the work
     * is than to walk there and back - and a table eight blocks away can mean climbing out of a
     * mineshaft, which is not eight blocks of walking at all.
     */
    static final int TABLE_SEARCH_RADIUS = 4;
    /** If the nearest table is further than this, the bot prefers placing a fresh one. */
    private static final int NEARBY_TABLE_RADIUS_SQR = 3 * 3;
    /** How many times we will try to open a table before concluding it's unreachable. */
    private static final int MAX_OPEN_ATTEMPTS = 4;
    /** How many ticks we will keep trying to place at one spot before picking another. */
    private static final int MAX_PLACE_TICKS = 40;

    private final Set<Long> unreachableTables = new HashSet<>();
    private final Set<Long> badPlacementSpots = new HashSet<>();

    private int cooldown;
    private int openAttempts;
    private long currentTableKey = -1;
    private BlockPos tablePos;
    private GotoTask approach;
    private int tableSearchRadius = TABLE_SEARCH_RADIUS;
    private int placingTicks;
    private BlockPos placingSpot;
    private BlockPos fallbackTablePos;
    /** One bounded attempt to reconnect to a surface table after a route failed from a pocket. */
    private SurfaceRecoveryTask tableRecovery;
    private BlockPos tableForRecovery;
    private boolean attemptedTableRecovery;
    private final StatusText status = new StatusText();

    /**
     * Whether a crafting table is close enough to use, counting the ones already remembered.
     * <p>
     * Callers that need a 3×3 recipe have to know this <em>before</em> they commit to it, because
     * the answer decides whether they first have to spend four planks on a table - and a recipe that
     * needs a table it cannot reach fails with a message about the recipe.
     */
    static boolean tableInReach(BotContext ctx, int radius) {
        if (CraftingTableMemory.get().findNearest(ctx, ctx.player.blockPosition(), radius) != null) {
            return true;
        }
        return BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                Set.of(Blocks.CRAFTING_TABLE), radius, ctx.level.getMinY(), ctx.level.getMaxY(),
                Set.of()) != null;
    }

    /** Sets how far the bot will look for an existing table before placing a new one. */
    void setSearchRadius(int radius) {
        this.tableSearchRadius = Math.max(1, radius);
    }

    int searchRadius() {
        return tableSearchRadius;
    }

    /** What the table work is doing right now, for the card's own status line. */
    String status() {
        return status.text();
    }

    /** The same, still keyed, so the owning card keeps the meaning as well as the words. */
    StatusText statusLine() {
        return status;
    }

    /**
     * Ensures a crafting table exists nearby, we're standing next to it, and it's open.
     *
     * @return SUCCESS once a 3×3 grid is open, RUNNING while working towards one, FAILED when there
     *         is no table to be had
     */
    TaskStatus ensureOpen(BotContext ctx) {
        // Checked before the menu itself so that opening a table still costs the same settling
        // ticks it always did: the reply that opens the screen and the reply that fills it are two
        // packets, and acting on the first one leaves the grid contents still in flight.
        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }
        if (ctx.player.containerMenu instanceof CraftingMenu) {
            return TaskStatus.SUCCESS;
        }

        if (tableRecovery != null) {
            TaskStatus recovered = tableRecovery.tick(ctx);
            status.set("lune.status.crafting_table_access.returning_table", tableRecovery.statusLine());
            if (recovered == TaskStatus.RUNNING) {
                return TaskStatus.RUNNING;
            }

            tableRecovery.stop(ctx);
            tableRecovery = null;
            if (recovered == TaskStatus.SUCCESS && tableForRecovery != null) {
                // SurfaceRecoveryTask changed the position, so allow the same remembered table to
                // be selected again. The recovery is deliberately one-shot for this card; if the
                // route still fails, the table is genuinely not usable from this side.
                CraftingTableMemory.get().remember(tableForRecovery);
                unreachableTables.remove(tableForRecovery.asLong());
                tablePos = null;
                fallbackTablePos = null;
                tableForRecovery = null;
                status.set("lune.status.crafting_table_access.back_dry_ground_retrying_remembered");
                return TaskStatus.RUNNING;
            }

            if (tableForRecovery != null) {
                CraftingTableMemory.get().markUnreachable(tableForRecovery);
            }
            tableForRecovery = null;
            status.set("lune.status.crafting_table_access.couldnt_reconnect_remembered_table");
            return TaskStatus.FAILED;
        }

        // Forget a table that has been broken or removed.
        if (tablePos != null && !ctx.level.getBlockState(tablePos).is(Blocks.CRAFTING_TABLE)) {
            CraftingTableMemory.get().forget(tablePos);
            unreachableTables.add(tablePos.asLong());
            tablePos = null;
        }

        // Track open attempts per table so we don't spin on an unreachable one forever.
        if (tablePos == null) {
            currentTableKey = -1;
            openAttempts = 0;
        } else if (currentTableKey != tablePos.asLong()) {
            currentTableKey = tablePos.asLong();
            openAttempts = 0;
        }

        if (tablePos == null && fallbackTablePos == null && placingSpot == null) {
            // First, try the tables the bot has already seen. If none are available in range, the
            // ordinary scanner still runs.
            tablePos = CraftingTableMemory.get().findNearest(ctx, ctx.player.blockPosition(), tableSearchRadius);

            if (tablePos == null) {
                tablePos = BlockScanner.findNearest(ctx.level, ctx.player.blockPosition(),
                        Set.of(Blocks.CRAFTING_TABLE), tableSearchRadius,
                        ctx.level.getMinY(), ctx.level.getMaxY(), unreachableTables);
                if (tablePos != null) {
                    CraftingTableMemory.get().remember(tablePos);
                }
            }

            // The nearest table is far away. Rather than hiking back, try to place a fresh one where
            // the bot is standing. This keeps the mining site alive and avoids starting a fresh dig.
            if (tablePos != null && ctx.player.blockPosition().distSqr(tablePos) > NEARBY_TABLE_RADIUS_SQR
                    && InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)) {
                fallbackTablePos = tablePos;
                tablePos = null;
            }
        }

        // No close table in the world; place one if we can.
        if (tablePos == null) {
            if (!InventoryHelper.has(ctx.player, Items.CRAFTING_TABLE, 1)) {
                if (fallbackTablePos != null) {
                    tablePos = fallbackTablePos;
                    fallbackTablePos = null;
                    return TaskStatus.RUNNING;
                }
                status.set("lune.status.crafting_table_access.need_crafting_table");
                return TaskStatus.FAILED;
            }

            if (placingSpot == null) {
                placingSpot = BlockPlacer.findPlacementSpot(ctx, badPlacementSpots);
                placingTicks = 0;
            }

            if (placingSpot == null) {
                if (fallbackTablePos != null) {
                    tablePos = fallbackTablePos;
                    fallbackTablePos = null;
                    return TaskStatus.RUNNING;
                }
                status.set("lune.status.crafting_table_access.nowhere_put_crafting_table");
                return TaskStatus.FAILED;
            }

            // Only commit the spot once the block is actually there. tryPlace may take several
            // ticks to finish turning and the server to confirm, and acting as though the table
            // already exists makes the next tick try to open empty air.
            BlockPlacer.PlacementResult placement = BlockPlacer.tryPlace(
                    ctx, Blocks.CRAFTING_TABLE, placingSpot);
            if (placement == BlockPlacer.PlacementResult.PLACED
                    || placement == BlockPlacer.PlacementResult.ALREADY_PRESENT) {
                tablePos = placingSpot;
                placingSpot = null;
                placingTicks = 0;
                fallbackTablePos = null;
                CraftingTableMemory.get().remember(tablePos);
                status.set("lune.status.crafting_table_access.placed_crafting_table");
                cooldown = ACTION_COOLDOWN;
                return TaskStatus.RUNNING;
            }

            if (!placement.isTransient()) {
                badPlacementSpots.add(placingSpot.asLong());
                placingSpot = null;
                placingTicks = 0;
                status.set("lune.status.crafting_table_access.cant_place_trying_another_spot", placement.displayName());
                return TaskStatus.RUNNING;
            }

            placingTicks++;
            if (placingTicks >= MAX_PLACE_TICKS) {
                badPlacementSpots.add(placingSpot.asLong());
                placingSpot = null;
                placingTicks = 0;
                status.set("lune.status.crafting_table_access.cant_place_trying_another_spot_2");
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.crafting_table_access.placing_crafting_table");
            return TaskStatus.RUNNING;
        }

        // Close enough to interact? Try to open it.
        if (inReach(ctx, tablePos)) {
            if (openAttempts >= MAX_OPEN_ATTEMPTS) {
                unreachableTables.add(tablePos.asLong());
                CraftingTableMemory.get().markUnreachable(tablePos);
                tablePos = null;
                placingSpot = null;
                fallbackTablePos = null;
                status.set("lune.status.crafting_table_access.table_cant_opened_from_here");
                return TaskStatus.RUNNING;
            }

            if (BlockPlacer.use(ctx, tablePos)) {
                openAttempts++;
                status.set("lune.status.crafting_table_access.opening_crafting_table");
                cooldown = ACTION_COOLDOWN + 3;
                return TaskStatus.RUNNING;
            }
            status.set("lune.status.crafting_table_access.aiming_crafting_table");
            return TaskStatus.RUNNING;
        }

        // Need to walk closer. Allow breaking light obstructions (leaves, tall grass) so a table
        // placed or found behind foliage is still usable.
        if (approach == null) {
            approach = new GotoTask(tableApproachGoal(tablePos), false, true);
            approach.start(ctx);
        }
        TaskStatus walk = approach.tick(ctx);
        if (walk == TaskStatus.SUCCESS) {
            approach.stop(ctx);
            approach = null;
            // A path goal can be satisfied on the same tick that terrain or a collision box makes
            // the actual interaction impossible. Do not rebuild that already-satisfied goal every
            // tick: remember the table as unusable from this side and let normal table selection
            // or placement make a different decision.
            if (!inReach(ctx, tablePos)) {
                unreachableTables.add(tablePos.asLong());
                CraftingTableMemory.get().markUnreachable(tablePos);
                tablePos = null;
                placingSpot = null;
                fallbackTablePos = null;
                status.set("lune.status.crafting_table_access.arrived_beside_blocked_table_trying");
                return TaskStatus.RUNNING;
            }
            return TaskStatus.RUNNING; // now in reach, open next tick
        }
        if (walk == TaskStatus.FAILED) {
            approach.stop(ctx);
            approach = null;
            unreachableTables.add(tablePos.asLong());
            // A long-route craft may have started at the bottom of a self-dug staircase. Give the
            // shared route a chance to reconnect with visible dry ground before declaring its
            // remembered table unusable. Ordinary local crafts keep their old bounded failure.
            if (tableSearchRadius > TABLE_SEARCH_RADIUS
                    && ctx.level.dimension() == net.minecraft.world.level.Level.OVERWORLD
                    && SurfaceRecoveryTask.needsDryGroundRecovery(ctx)
                    && tableRecovery == null
                    && !attemptedTableRecovery) {
                tableForRecovery = tablePos;
                tablePos = null;
                fallbackTablePos = null;
                attemptedTableRecovery = true;
                tableRecovery = new SurfaceRecoveryTask();
                tableRecovery.start(ctx);
                status.set("lune.status.crafting_table_access.cant_reach_table_reconnecting_dry_ground");
                return TaskStatus.RUNNING;
            }
            CraftingTableMemory.get().markUnreachable(tablePos);
            tablePos = null;
            placingSpot = null;
            fallbackTablePos = null;
            status.set("lune.status.crafting_table_access.cant_reach_table_trying_another");
            return TaskStatus.RUNNING;
        }
        status.set("lune.status.crafting_table_access.walking_crafting_table");
        return TaskStatus.RUNNING;
    }

    /** Releases the walk, if one is in progress. The caller owns closing the menu. */
    void stop(BotContext ctx) {
        if (approach != null) {
            approach.stop(ctx);
            approach = null;
        }
        if (tableRecovery != null) {
            tableRecovery.stop(ctx);
            tableRecovery = null;
        }
    }

    private static boolean inReach(BotContext ctx, BlockPos pos) {
        Vec3 eye = ctx.player.getEyePosition();
        Vec3 centre = Vec3.atCenterOf(pos);
        if (eye.distanceToSqr(centre) > 20.0) {
            return false;
        }
        // Distance alone is not an interaction position. A player below a table can be within the
        // vanilla reach radius while a stone/dirt lip blocks every face; treating that as ready
        // leaves the card in its aiming loop forever. Keep the approach goal alive until the
        // actual table centre is the first block hit by the player's ray.
        return BlockPlacer.hasLineOfSight(ctx, pos);
    }

    /**
     * Stand on a block at the table's own height. Keeping the candidate set explicit prevents a
     * player one block below a table from satisfying a distance heuristic while remaining unable
     * to click any of its faces.
     */
    private static Goals.Any tableApproachGoal(BlockPos table) {
        return new Goals.Any(List.of(
                new Goals.Block(table.north()),
                new Goals.Block(table.south()),
                new Goals.Block(table.east()),
                new Goals.Block(table.west()),
                new Goals.Block(table.north().east()),
                new Goals.Block(table.north().west()),
                new Goals.Block(table.south().east()),
                new Goals.Block(table.south().west())));
    }
}
