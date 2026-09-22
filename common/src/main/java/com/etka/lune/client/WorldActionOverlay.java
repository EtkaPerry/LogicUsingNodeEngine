package com.etka.lune.client;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.DebugInfo;
import com.etka.lune.bot.util.Leash;
import com.etka.lune.compat.Screens;
import com.etka.lune.config.BotConfig;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Small, optional world-space explanation of the bot's live intention.
 *
 * <p>The bot already publishes these markers through {@link DebugInfo}; keeping the renderer here
 * means Mine, Harvest, pathing, bridge/recovery and every task get the same visual language.
 * Red is a block being broken, cyan is the next route block, yellow is a placement, and the dark
 * ground aura is the boundary published by {@link Leash} for Stay Near.</p>
 *
 * <p>Geometry goes through the {@link SubmitNodeCollector} rather than a buffer source. Lune builds
 * one source tree for every Minecraft version it ships for, so this class may only use what all of
 * them share: the collector is unchanged from 26.1.2 through 26.3, whereas {@code MultiBufferSource}
 * left the game in 26.2, and the camera is read off the {@link LevelRenderState} because
 * {@code GameRenderer}'s accessor was renamed in the same release.</p>
 */
public final class WorldActionOverlay {

    /** World colours are packed as RGBA for VertexConsumer.setColor. */
    private static final int BREAK_RED = 0xFF3D3DEF;
    private static final int MOVE_CYAN = 0x4BD7FFFF;
    private static final int PLACE_YELLOW = 0xFFD166FF;
    private static final int TARGET_ORANGE = 0xFF9F43E8;
    /**
     * The same four markers in Okabe-Ito, for when hue is the only thing telling them apart.
     *
     * <p>Red for breaking and orange for the target are the pair this most needed: they are the
     * two most important markers and they are adjacent hues, which is the combination deuteranopia
     * flattens. Vermillion and reddish purple are separated by lightness as well, so they survive
     * it. Packed RGBA here to match the rest of the world colours above.</p>
     */
    private static final int BREAK_SAFE = 0xD55E00FF;
    private static final int MOVE_SAFE = 0x56B4E9FF;
    private static final int PLACE_SAFE = 0xF0E442FF;
    private static final int TARGET_SAFE = 0xCC79A7FF;
    private static final int AREA_DARK = 0x00000030;
    /**
     * The Stay Near boundary.
     *
     * <p>Sky blue rather than the near-black it used to be. Every other marker in this class is a
     * saturated hue and this one - the only marker that is a safety limit rather than a target -
     * was #2A3142 drawn on the ground, which against stone or at night was not visible at all.</p>
     */
    private static final int AREA_RING = 0x56B4E9C0;
    /** GUI colours use the vanilla ARGB packing. */
    private static final int BREAK_HUD = 0xFFFF3D3D;
    private static final int MOVE_HUD = 0xFF4BD7FF;
    private static final int PLACE_HUD = 0xFFFFD166;
    private static final int TARGET_HUD = 0xFFFF9F43;
    private static final int BREAK_HUD_SAFE = 0xFFD55E00;
    private static final int MOVE_HUD_SAFE = 0xFF56B4E9;
    private static final int PLACE_HUD_SAFE = 0xFFF0E442;
    private static final int TARGET_HUD_SAFE = 0xFFCC79A7;
    private static final int MARKER_FILL_ALPHA = 42;
    private static final int AREA_SEGMENTS = 64;

    private WorldActionOverlay() {}

    /**
     * Called while the level renderer collects this frame's submits. The pose stack is the one
     * vanilla hands its own features, so it is camera-relative, and the submitted geometry is drawn
     * later in the pass its render type belongs to.
     */
    public static void render(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector collector) {
        Minecraft mc = Minecraft.getInstance();
        BotConfig config = BotConfig.get();
        if (!config.showActionMarkers || mc.player == null || mc.level == null
                || Screens.current(mc) != null || state == null || collector == null) {
            return;
        }

        DebugInfo debug = BotEngine.get().getDebug();
        Leash leash = Leash.get();
        if (debug.breakTarget == null && debug.movementTarget == null
                && debug.placementTarget == null && debug.targetPos == null && !leash.active()) {
            return;
        }

        Vec3 cameraPosition = state.cameraRenderState.pos;
        poseStack.pushPose();
        poseStack.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);

        float pulse = 0.85F + 0.15F
                * Mth.sin((mc.level.getGameTime() % 40L) * (Mth.TWO_PI / 40.0F));
        boolean safe = config.colourBlindSafe();
        if (debug.breakTarget != null) {
            drawMarker(poseStack, collector, debug.breakTarget,
                    safe ? BREAK_SAFE : BREAK_RED, pulse);
        }
        if (debug.movementTarget != null) {
            drawMarker(poseStack, collector, debug.movementTarget,
                    safe ? MOVE_SAFE : MOVE_CYAN, pulse);
        }
        if (debug.placementTarget != null) {
            drawMarker(poseStack, collector, debug.placementTarget,
                    safe ? PLACE_SAFE : PLACE_YELLOW, pulse);
        }
        if (debug.targetPos != null && !debug.targetPos.equals(debug.breakTarget)
                && !debug.targetPos.equals(debug.movementTarget)
                && !debug.targetPos.equals(debug.placementTarget)) {
            drawMarker(poseStack, collector, debug.targetPos,
                    safe ? TARGET_SAFE : TARGET_ORANGE, pulse);
        }
        if (leash.active()) {
            drawLeashArea(poseStack, collector, leash);
        }
        poseStack.popPose();
    }

    private static void drawMarker(PoseStack poseStack, SubmitNodeCollector collector,
                                   BlockPos pos, int colour, float pulse) {
        int alpha = Math.clamp(Math.round(MARKER_FILL_ALPHA * pulse), 20, 60);
        int fill = (colour & 0xFFFFFF00) | alpha;
        collector.submitCustomGeometry(poseStack, RenderTypes.debugFilledBox(),
                (pose, consumer) -> drawBox(pose, consumer, pos, fill, 0.02F, false));
        collector.submitCustomGeometry(poseStack, RenderTypes.linesTranslucent(),
                (pose, consumer) -> drawBox(pose, consumer, pos, colour, 0.025F, true));
    }

    private static void drawBox(PoseStack.Pose pose, VertexConsumer consumer, BlockPos pos,
                                int colour, float expand, boolean outline) {
        float x0 = pos.getX() - expand;
        float y0 = pos.getY() - expand;
        float z0 = pos.getZ() - expand;
        float x1 = pos.getX() + 1.0F + expand;
        float y1 = pos.getY() + 1.0F + expand;
        float z1 = pos.getZ() + 1.0F + expand;
        if (outline) {
            line(consumer, pose, x0, y0, z0, x1, y0, z0, colour);
            line(consumer, pose, x1, y0, z0, x1, y0, z1, colour);
            line(consumer, pose, x1, y0, z1, x0, y0, z1, colour);
            line(consumer, pose, x0, y0, z1, x0, y0, z0, colour);
            line(consumer, pose, x0, y1, z0, x1, y1, z0, colour);
            line(consumer, pose, x1, y1, z0, x1, y1, z1, colour);
            line(consumer, pose, x1, y1, z1, x0, y1, z1, colour);
            line(consumer, pose, x0, y1, z1, x0, y1, z0, colour);
            line(consumer, pose, x0, y0, z0, x0, y1, z0, colour);
            line(consumer, pose, x1, y0, z0, x1, y1, z0, colour);
            line(consumer, pose, x1, y0, z1, x1, y1, z1, colour);
            line(consumer, pose, x0, y0, z1, x0, y1, z1, colour);
            return;
        }

        quad(consumer, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, colour);
        quad(consumer, pose, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, colour);
        quad(consumer, pose, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, colour);
        quad(consumer, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, colour);
        quad(consumer, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, colour);
        quad(consumer, pose, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, colour);
    }

    private static void quad(VertexConsumer consumer, PoseStack.Pose pose,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             int colour) {
        vertex(consumer, pose, ax, ay, az, colour);
        vertex(consumer, pose, bx, by, bz, colour);
        vertex(consumer, pose, cx, cy, cz, colour);
        vertex(consumer, pose, dx, dy, dz, colour);
    }

    private static void line(VertexConsumer consumer, PoseStack.Pose pose,
                             float ax, float ay, float az, float bx, float by, float bz,
                             int colour) {
        vertex(consumer, pose, ax, ay, az, colour)
                .setNormal(pose, 0.0F, 1.0F, 0.0F)
                .setLineWidth(1.0F);
        vertex(consumer, pose, bx, by, bz, colour)
                .setNormal(pose, 0.0F, 1.0F, 0.0F)
                .setLineWidth(1.0F);
    }

    private static VertexConsumer vertex(VertexConsumer consumer, PoseStack.Pose pose,
                                         float x, float y, float z, int colour) {
        return consumer.addVertex(pose, x, y, z)
                .setColor((colour >>> 24) & 0xFF, (colour >>> 16) & 0xFF,
                        (colour >>> 8) & 0xFF, colour & 0xFF);
    }

    private static void drawLeashArea(PoseStack poseStack, SubmitNodeCollector collector, Leash leash) {
        BlockPos anchor = leash.anchor();
        int radius = leash.radius();
        if (anchor == null || radius <= 0) {
            return;
        }

        float y = anchor.getY() + 0.035F;
        float centreX = anchor.getX() + 0.5F;
        float centreZ = anchor.getZ() + 0.5F;
        collector.submitCustomGeometry(poseStack, RenderTypes.debugTriangleFan(), (pose, fill) -> {
            vertex(fill, pose, centreX, y, centreZ, AREA_DARK);
            for (int i = 0; i <= AREA_SEGMENTS; i++) {
                double angle = i * Mth.TWO_PI / AREA_SEGMENTS;
                vertex(fill, pose,
                        centreX + Mth.cos((float) angle) * radius,
                        y,
                        centreZ + Mth.sin((float) angle) * radius,
                        AREA_DARK);
            }
        });

        collector.submitCustomGeometry(poseStack, RenderTypes.linesTranslucent(), (pose, ring) -> {
            for (int i = 0; i < AREA_SEGMENTS; i++) {
                double a = i * Mth.TWO_PI / AREA_SEGMENTS;
                double b = (i + 1) * Mth.TWO_PI / AREA_SEGMENTS;
                line(ring, pose,
                        centreX + Mth.cos((float) a) * radius, y, centreZ + Mth.sin((float) a) * radius,
                        centreX + Mth.cos((float) b) * radius, y, centreZ + Mth.sin((float) b) * radius,
                        AREA_RING);
            }
        });
    }

    /**
     * Forge 26 does not expose a level-render stage event. Its GUI event still gets a useful,
     * non-invasive fallback: a small screen-space marker at the projected block centre. Fabric and
     * NeoForge use the real world-space renderer above.
     */
    public static void renderHud(GuiGraphicsExtractor extractor) {
        Minecraft mc = Minecraft.getInstance();
        BotConfig config = BotConfig.get();
        if (!config.showActionMarkers || mc.player == null || mc.level == null || Screens.current(mc) != null) {
            return;
        }

        DebugInfo debug = BotEngine.get().getDebug();
        boolean safe = config.colourBlindSafe();
        drawHudMarker(extractor, mc, debug.breakTarget, safe ? BREAK_HUD_SAFE : BREAK_HUD,
                "lune.gui.overlay.marker.break");
        drawHudMarker(extractor, mc, debug.movementTarget, safe ? MOVE_HUD_SAFE : MOVE_HUD,
                "lune.gui.overlay.marker.go");
        drawHudMarker(extractor, mc, debug.placementTarget, safe ? PLACE_HUD_SAFE : PLACE_HUD,
                "lune.gui.overlay.marker.place");
        if (debug.targetPos != null && !debug.targetPos.equals(debug.breakTarget)
                && !debug.targetPos.equals(debug.movementTarget)
                && !debug.targetPos.equals(debug.placementTarget)) {
            drawHudMarker(extractor, mc, debug.targetPos, safe ? TARGET_HUD_SAFE : TARGET_HUD,
                    "lune.gui.overlay.target");
        }
    }

    /** {@code labelKey} rather than a word: this is the only marker path that renders text. */
    private static void drawHudMarker(GuiGraphicsExtractor extractor, Minecraft mc, BlockPos pos,
                                      int colour, String labelKey) {
        if (pos == null) {
            return;
        }
        String label = Lang.get(labelKey);
        Vec3 projected = mc.gameRenderer.projectPointToScreen(Vec3.atCenterOf(pos));
        if (!Double.isFinite(projected.x) || !Double.isFinite(projected.y)
                || projected.z < -1.0 || projected.z > 1.0) {
            return;
        }
        int x = Mth.floor((float) ((projected.x + 1.0) * 0.5 * extractor.guiWidth()));
        int y = Mth.floor((float) ((1.0 - projected.y) * 0.5 * extractor.guiHeight()));
        int radius = 7;
        extractor.outline(x - radius, y - radius, radius * 2, radius * 2, colour);
        extractor.text(mc.font, label, x + radius + 2, y - 4, colour);
    }
}
