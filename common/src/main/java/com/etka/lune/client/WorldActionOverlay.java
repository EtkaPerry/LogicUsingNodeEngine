package com.etka.lune.client;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.DebugInfo;
import com.etka.lune.bot.util.Leash;
import com.etka.lune.config.BotConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
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
 */
public final class WorldActionOverlay {

    /** World colours are packed as RGBA for VertexConsumer.setColor. */
    private static final int BREAK_RED = 0xFF3D3DEF;
    private static final int MOVE_CYAN = 0x4BD7FFFF;
    private static final int PLACE_YELLOW = 0xFFD166FF;
    private static final int TARGET_ORANGE = 0xFF9F43E8;
    private static final int AREA_DARK = 0x00000030;
    private static final int AREA_RING = 0x2A3142B0;
    /** GUI colours use the vanilla ARGB packing. */
    private static final int BREAK_HUD = 0xFFFF3D3D;
    private static final int MOVE_HUD = 0xFF4BD7FF;
    private static final int PLACE_HUD = 0xFFFFD166;
    private static final int TARGET_HUD = 0xFFFF9F43;
    private static final int MARKER_FILL_ALPHA = 42;
    private static final int AREA_SEGMENTS = 64;

    private WorldActionOverlay() {}

    /** Called from a level-render stage while the pose stack is camera-relative. */
    public static void render(PoseStack poseStack, Camera camera, MultiBufferSource buffers) {
        Minecraft mc = Minecraft.getInstance();
        BotConfig config = BotConfig.get();
        if (!config.showActionMarkers || mc.player == null || mc.level == null
                || mc.screen != null || camera == null || buffers == null) {
            return;
        }

        DebugInfo debug = BotEngine.get().getDebug();
        Leash leash = Leash.get();
        if (debug.breakTarget == null && debug.movementTarget == null
                && debug.placementTarget == null && debug.targetPos == null && !leash.active()) {
            return;
        }

        Vec3 cameraPosition = camera.position();
        poseStack.pushPose();
        poseStack.translate(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);

        float pulse = 0.85F + 0.15F
                * Mth.sin((mc.level.getGameTime() % 40L) * (Mth.TWO_PI / 40.0F));
        if (debug.breakTarget != null) {
            drawMarker(poseStack, buffers, debug.breakTarget, BREAK_RED, pulse);
        }
        if (debug.movementTarget != null) {
            drawMarker(poseStack, buffers, debug.movementTarget, MOVE_CYAN, pulse);
        }
        if (debug.placementTarget != null) {
            drawMarker(poseStack, buffers, debug.placementTarget, PLACE_YELLOW, pulse);
        }
        if (debug.targetPos != null && !debug.targetPos.equals(debug.breakTarget)
                && !debug.targetPos.equals(debug.movementTarget)
                && !debug.targetPos.equals(debug.placementTarget)) {
            drawMarker(poseStack, buffers, debug.targetPos, TARGET_ORANGE, pulse);
        }
        if (leash.active()) {
            drawLeashArea(poseStack, buffers, leash);
        }
        poseStack.popPose();
    }

    private static void drawMarker(PoseStack poseStack, MultiBufferSource buffers,
                                   BlockPos pos, int colour, float pulse) {
        int alpha = Math.clamp(Math.round(MARKER_FILL_ALPHA * pulse), 20, 60);
        int fill = (colour & 0xFFFFFF00) | alpha;
        drawBox(poseStack, buffers.getBuffer(RenderTypes.debugFilledBox()), pos, fill,
                0.02F, false);
        drawBox(poseStack, buffers.getBuffer(RenderTypes.linesTranslucent()), pos, colour,
                0.025F, true);
    }

    private static void drawBox(PoseStack poseStack, VertexConsumer consumer, BlockPos pos,
                                int colour, float expand, boolean outline) {
        float x0 = pos.getX() - expand;
        float y0 = pos.getY() - expand;
        float z0 = pos.getZ() - expand;
        float x1 = pos.getX() + 1.0F + expand;
        float y1 = pos.getY() + 1.0F + expand;
        float z1 = pos.getZ() + 1.0F + expand;
        if (outline) {
            line(consumer, poseStack, x0, y0, z0, x1, y0, z0, colour);
            line(consumer, poseStack, x1, y0, z0, x1, y0, z1, colour);
            line(consumer, poseStack, x1, y0, z1, x0, y0, z1, colour);
            line(consumer, poseStack, x0, y0, z1, x0, y0, z0, colour);
            line(consumer, poseStack, x0, y1, z0, x1, y1, z0, colour);
            line(consumer, poseStack, x1, y1, z0, x1, y1, z1, colour);
            line(consumer, poseStack, x1, y1, z1, x0, y1, z1, colour);
            line(consumer, poseStack, x0, y1, z1, x0, y1, z0, colour);
            line(consumer, poseStack, x0, y0, z0, x0, y1, z0, colour);
            line(consumer, poseStack, x1, y0, z0, x1, y1, z0, colour);
            line(consumer, poseStack, x1, y0, z1, x1, y1, z1, colour);
            line(consumer, poseStack, x0, y0, z1, x0, y1, z1, colour);
            return;
        }

        quad(consumer, poseStack, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, colour);
        quad(consumer, poseStack, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, colour);
        quad(consumer, poseStack, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, colour);
        quad(consumer, poseStack, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, colour);
        quad(consumer, poseStack, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, colour);
        quad(consumer, poseStack, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, colour);
    }

    private static void quad(VertexConsumer consumer, PoseStack poseStack,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             int colour) {
        vertex(consumer, poseStack, ax, ay, az, colour);
        vertex(consumer, poseStack, bx, by, bz, colour);
        vertex(consumer, poseStack, cx, cy, cz, colour);
        vertex(consumer, poseStack, dx, dy, dz, colour);
    }

    private static void line(VertexConsumer consumer, PoseStack poseStack,
                             float ax, float ay, float az, float bx, float by, float bz,
                             int colour) {
        vertex(consumer, poseStack, ax, ay, az, colour)
                .setNormal(poseStack.last(), 0.0F, 1.0F, 0.0F)
                .setLineWidth(1.0F);
        vertex(consumer, poseStack, bx, by, bz, colour)
                .setNormal(poseStack.last(), 0.0F, 1.0F, 0.0F)
                .setLineWidth(1.0F);
    }

    private static VertexConsumer vertex(VertexConsumer consumer, PoseStack poseStack,
                                         float x, float y, float z, int colour) {
        return consumer.addVertex(poseStack.last(), x, y, z)
                .setColor((colour >>> 24) & 0xFF, (colour >>> 16) & 0xFF,
                        (colour >>> 8) & 0xFF, colour & 0xFF);
    }

    private static void drawLeashArea(PoseStack poseStack, MultiBufferSource buffers, Leash leash) {
        BlockPos anchor = leash.anchor();
        int radius = leash.radius();
        if (anchor == null || radius <= 0) {
            return;
        }

        float y = anchor.getY() + 0.035F;
        float centreX = anchor.getX() + 0.5F;
        float centreZ = anchor.getZ() + 0.5F;
        VertexConsumer fill = buffers.getBuffer(RenderTypes.debugTriangleFan());
        vertex(fill, poseStack, centreX, y, centreZ, AREA_DARK);
        for (int i = 0; i <= AREA_SEGMENTS; i++) {
            double angle = i * Mth.TWO_PI / AREA_SEGMENTS;
            vertex(fill, poseStack,
                    centreX + Mth.cos((float) angle) * radius,
                    y,
                    centreZ + Mth.sin((float) angle) * radius,
                    AREA_DARK);
        }

        VertexConsumer ring = buffers.getBuffer(RenderTypes.linesTranslucent());
        for (int i = 0; i < AREA_SEGMENTS; i++) {
            double a = i * Mth.TWO_PI / AREA_SEGMENTS;
            double b = (i + 1) * Mth.TWO_PI / AREA_SEGMENTS;
            line(ring, poseStack,
                    centreX + Mth.cos((float) a) * radius, y, centreZ + Mth.sin((float) a) * radius,
                    centreX + Mth.cos((float) b) * radius, y, centreZ + Mth.sin((float) b) * radius,
                    AREA_RING);
        }
    }

    /**
     * Forge 26 does not expose a level-render stage event. Its GUI event still gets a useful,
     * non-invasive fallback: a small screen-space marker at the projected block centre. Fabric and
     * NeoForge use the real world-space renderer above.
     */
    public static void renderHud(GuiGraphicsExtractor extractor) {
        Minecraft mc = Minecraft.getInstance();
        BotConfig config = BotConfig.get();
        if (!config.showActionMarkers || mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }

        DebugInfo debug = BotEngine.get().getDebug();
        drawHudMarker(extractor, mc, debug.breakTarget, BREAK_HUD, "break");
        drawHudMarker(extractor, mc, debug.movementTarget, MOVE_HUD, "go");
        drawHudMarker(extractor, mc, debug.placementTarget, PLACE_HUD, "place");
        if (debug.targetPos != null && !debug.targetPos.equals(debug.breakTarget)
                && !debug.targetPos.equals(debug.movementTarget)
                && !debug.targetPos.equals(debug.placementTarget)) {
            drawHudMarker(extractor, mc, debug.targetPos, TARGET_HUD, "target");
        }
    }

    private static void drawHudMarker(GuiGraphicsExtractor extractor, Minecraft mc, BlockPos pos,
                                      int colour, String label) {
        if (pos == null) {
            return;
        }
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
