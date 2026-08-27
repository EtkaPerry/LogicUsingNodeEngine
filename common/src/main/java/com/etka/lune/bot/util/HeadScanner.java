package com.etka.lune.bot.util;

import com.etka.lune.bot.BotContext;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * Looks around the way a person does.
 * <p>
 * A player does not pivot through four exact 90° stops every time they want something. They look
 * where they are already facing, flick their eyes to either side, and only turn all the way round
 * if that came up empty. {@link Style} is that difference: a scan starts narrow and cheap, and
 * {@link #escalate(LocalPlayer)} widens it only when the narrow look failed.
 * <p>
 * The offsets are deliberately not round numbers and are jittered per scan, so two scans from the
 * same spot never trace the same path - a fixed 0/90/180/270 pattern is both unnatural to watch and
 * a trivial signature to spot.
 */
public final class HeadScanner {

    /** How wide a look to take. Each step up costs more time but covers more ground. */
    public enum Style {
        /** Straight ahead and a flick to either side. What you do before committing to anything. */
        GLANCE(new float[]{0.0F, -46.0F, 44.0F}, 1),
        /** Ahead, both sides, and over both shoulders - full coverage without marching in 90s. */
        SWEEP(new float[]{0.0F, -68.0F, 70.0F, 142.0F, -140.0F}, 2),
        /** A deliberate, slow turn all the way round, glancing up and down at each stop. */
        FULL(new float[]{0.0F, 71.0F, 143.0F, 215.0F, 287.0F}, 3);

        private final float[] yawOffsets;
        private final int verticalGlances;

        Style(float[] yawOffsets, int verticalGlances) {
            this.yawOffsets = yawOffsets;
            this.verticalGlances = verticalGlances;
        }

        /**
         * The next wider style, or null when there is nothing left worth checking.
         * <p>
         * {@link #SWEEP} is the end of the line even though {@link #FULL} exists: sweep already
         * covers every direction, so escalating into a full turn would only re-check ground the
         * sweep just covered. {@code FULL} is for a player who explicitly wants the slow, thorough
         * turn, not something to fall into automatically.
         */
        public Style wider() {
            return this == GLANCE ? SWEEP : null;
        }
    }

    /** Level, up, then down - the order a player checks when they are looking for something. */
    private static final float[] VERTICAL_OFFSETS = {0.0F, -24.0F, 22.0F};
    private static final int MAX_TURN_TICKS = 120;
    private static final float SETTLED_TOLERANCE = 3.0F;
    private static final float MICRO_JITTER_DEGREES = 1.5F;
    /** Per-scan randomisation of each view angle, so no two scans trace the same path. */
    private static final float VIEW_JITTER_DEGREES = 7.0F;

    private final RandomSource random = RandomSource.create();
    private final Style baseStyle;

    private Style style;
    private float[] yawOffsets;
    private float baseYaw;
    private int horizontalIndex;
    private int verticalIndex;
    private int turnTicks;
    private boolean turning;
    private boolean verticalGlance;
    private int microJitterTicks;
    private float microJitterYaw;
    private float microJitterPitch;

    /**
     * Defaults to {@link Style#SWEEP}: it still covers every direction, so callers that relied on
     * the old four-view scan lose no coverage, but it gets there in one flowing movement.
     */
    public HeadScanner() {
        this(Style.SWEEP);
    }

    public HeadScanner(Style style) {
        this.baseStyle = style;
        this.style = style;
        this.yawOffsets = style.yawOffsets.clone();
    }

    /** Starts a new scan from the player's current facing, at this scanner's base width. */
    public void reset(LocalPlayer player) {
        style = baseStyle;
        restart(player);
    }

    /**
     * Widens the scan after a narrow look found nothing, and restarts it from the current facing.
     *
     * @return false when the scan was already as wide as it goes, meaning the caller has genuinely
     *         looked everywhere and should move somewhere else instead
     */
    public boolean escalate(LocalPlayer player) {
        Style wider = style.wider();
        if (wider == null) {
            return false;
        }
        style = wider;
        restart(player);
        return true;
    }

    public Style style() {
        return style;
    }

    public boolean isTurning() {
        return turning;
    }

    public boolean isVerticalGlance() {
        return verticalGlance;
    }

    /**
     * Advances the eased turn for the current view, including vertical glances.
     *
     * @return true when the view has settled. Callers may scan on any tick - a player spots things
     *         mid-turn - but a settled view is when the whole view cone is worth trusting.
     */
    public boolean tickTurn(BotContext ctx) {
        float targetYaw = desiredYaw();
        float targetPitch = desiredPitch();

        // Subtle drift while holding a view, the way eyes never sit perfectly still.
        if (!turning && !verticalGlance && microJitterTicks == 0 && random.nextFloat() < 0.02F) {
            microJitterTicks = 3 + random.nextInt(5);
            microJitterYaw = (random.nextFloat() - 0.5F) * MICRO_JITTER_DEGREES * 2;
            microJitterPitch = (random.nextFloat() - 0.5F) * MICRO_JITTER_DEGREES * 2;
        }

        float actualYaw = targetYaw + (microJitterTicks > 0 ? microJitterYaw : 0);
        float actualPitch = targetPitch + (microJitterTicks > 0 ? microJitterPitch : 0);

        ctx.look.lookAtRotation(ctx.player, actualYaw, actualPitch);

        boolean settled = ctx.look.isLookingAtRotation(ctx.player, targetYaw, targetPitch, SETTLED_TOLERANCE);

        if (turning) {
            if (settled || ++turnTicks >= MAX_TURN_TICKS) {
                turning = false;
                turnTicks = 0;
                verticalGlance = verticalGlanceCount() > 1;
                verticalIndex = 0;
            }
            return false;
        }

        if (verticalGlance) {
            if (settled) {
                verticalIndex++;
                if (verticalIndex >= verticalGlanceCount()) {
                    verticalGlance = false;
                    verticalIndex = 0;
                    microJitterTicks = 2 + random.nextInt(4);
                }
            } else if (++turnTicks >= MAX_TURN_TICKS) {
                verticalGlance = false;
                verticalIndex = 0;
                turnTicks = 0;
            }
            return false;
        }

        if (microJitterTicks > 0) {
            microJitterTicks--;
            return false;
        }

        return true;
    }

    /** Moves to the next view. False when this scan has run out of views to try. */
    public boolean advance() {
        if (horizontalIndex + 1 >= yawOffsets.length) {
            return false;
        }
        horizontalIndex++;
        turnTicks = 0;
        turning = true;
        verticalGlance = false;
        verticalIndex = 0;
        microJitterTicks = 0;
        return true;
    }

    /** Marks the scan as finished without turning through the remaining views. */
    public void finish() {
        horizontalIndex = yawOffsets.length - 1;
        verticalIndex = verticalGlanceCount() - 1;
        turning = false;
        verticalGlance = false;
        turnTicks = 0;
        microJitterTicks = 0;
    }

    public int horizontalViewNumber() {
        return horizontalIndex + 1;
    }

    public int horizontalViewCount() {
        return yawOffsets.length;
    }

    public int verticalGlanceNumber() {
        return verticalIndex + 1;
    }

    public int verticalGlanceCount() {
        return style.verticalGlances;
    }

    /** How far round from where the scan started the current view sits. */
    public int relativeTurnDegrees() {
        return Math.round(yawOffsets[horizontalIndex]);
    }

    /** A human-readable account of what the bot is doing with its head right now. */
    public String status() {
        String where = describeView();
        if (turning) {
            return "looking " + where;
        }
        if (verticalGlance) {
            String dir = verticalIndex == 1 ? "up" : verticalIndex == 2 ? "down" : "level";
            return "glancing " + dir + ", " + where;
        }
        if (microJitterTicks > 0) {
            return "scanning " + where;
        }
        return "checked " + where;
    }

    private void restart(LocalPlayer player) {
        baseYaw = Mth.wrapDegrees(player.getYRot());
        yawOffsets = jittered(style.yawOffsets);
        horizontalIndex = 0;
        verticalIndex = 0;
        turnTicks = 0;
        // The first view is wherever the head already points, so there is nothing to turn to yet.
        turning = false;
        verticalGlance = style.verticalGlances > 1;
        microJitterTicks = 0;
    }

    private float[] jittered(float[] offsets) {
        float[] result = new float[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            // The first view is straight ahead by definition; nudging it would look like a flinch.
            float jitter = i == 0 ? 0.0F : (random.nextFloat() - 0.5F) * VIEW_JITTER_DEGREES * 2;
            result[i] = offsets[i] + jitter;
        }
        return result;
    }

    /** "ahead", "left", "over my right shoulder" - how a person would describe where they looked. */
    private String describeView() {
        float offset = Mth.wrapDegrees(yawOffsets[horizontalIndex]);
        float magnitude = Math.abs(offset);
        if (magnitude < 20.0F) {
            return "ahead";
        }
        String side = offset < 0 ? "left" : "right";
        if (magnitude < 100.0F) {
            return "to the " + side;
        }
        if (magnitude < 160.0F) {
            return "over my " + side + " shoulder";
        }
        return "behind me";
    }

    private float desiredYaw() {
        return Mth.wrapDegrees(baseYaw + yawOffsets[horizontalIndex]);
    }

    private float desiredPitch() {
        if (verticalGlance && verticalIndex < VERTICAL_OFFSETS.length) {
            return VERTICAL_OFFSETS[verticalIndex];
        }
        return 0.0F;
    }
}
