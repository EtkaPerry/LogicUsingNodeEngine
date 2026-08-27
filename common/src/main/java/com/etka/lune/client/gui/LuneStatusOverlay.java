package com.etka.lune.client.gui;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.DebugInfo;
import com.etka.lune.bot.Task;
import com.etka.lune.config.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/** A small in-world status card; F6 remains the full diagnostic overlay. */
public final class LuneStatusOverlay {

    private static final int MARGIN = 8;
    private static final int LINE_HEIGHT = 12;
    private static final int BACKGROUND = 0xD9161B24;
    private static final int BORDER = 0xFF3A4657;
    private static final int HEADER = 0xFFF4C95D;
    private static final int LABEL = 0xFF91A4BE;
    private static final int VALUE = 0xFFE6EAF0;
    private static final int PAUSED = 0xFFB6C5D8;
    private static final int DANGER = 0xFFFF7777;
    private static final int MAX_WIDTH = 360;
    private static final int ART_SIZE = 48;
    private static final int FRAME_SIZE = 96;
    private static final int FRAME_COUNT = 8;
    private static final int ATLAS_WIDTH = FRAME_SIZE * FRAME_COUNT;
    private static final int ATLAS_HEIGHT = FRAME_SIZE * 3;
    private static final Identifier MASCOT_BACK =
            Identifier.parse("lune:textures/gui/mascot/back.png");
    private static final Identifier MASCOT_SOUL =
            Identifier.parse("lune:textures/gui/mascot/soul.png");
    private static final Identifier MASCOT_FRONT =
            Identifier.parse("lune:textures/gui/mascot/front.png");
    private static final String ELLIPSIS = "...";

    private LuneStatusOverlay() {}

    public static void render(GuiGraphicsExtractor extractor) {
        Minecraft mc = Minecraft.getInstance();
        BotConfig config = BotConfig.get();
        BotEngine engine = BotEngine.get();
        if (!config.showLune || mc.player == null || mc.level == null || mc.screen != null
                || engine.isIdle()) {
            return;
        }

        DebugInfo debug = engine.getDebug();
        List<Line> lines = new ArrayList<>();
        boolean protecting = engine.isEmergencyProtectionActive();
        int headerColour = protecting ? DANGER : engine.isPaused() ? PAUSED : HEADER;
        String state = protecting ? "Protecting me" : engine.isPaused() ? "Paused"
                : engine.getCurrent() == null ? "Starting" : "Working";
        lines.add(new Line("Lune  " + state, headerColour));
        lines.add(new Line("doing  " + doing(engine, debug), VALUE));
        lines.add(new Line("going  " + going(debug), LABEL));
        if (!debug.nextTask.isBlank()) {
            lines.add(new Line("next   " + debug.nextTask, LABEL));
        }

        Font font = mc.font;
        int width = Math.min(MAX_WIDTH, Math.max(1, extractor.guiWidth() - 2 * MARGIN));
        int contentWidth = Math.max(120, width - ART_SIZE - 20);
        int height = lines.size() * LINE_HEIGHT + 12;
        int x = extractor.guiWidth() - width - MARGIN;
        int y = MARGIN;

        extractor.fill(x, y, x + width, y + height, BACKGROUND);
        extractor.fill(x, y, x + width, y + 2, BORDER);
        drawMascot(extractor, x + 8, y + 7, engine, Util.getMillis());
        var text = extractor.textRenderer();
        int lineY = y + 5;
        for (Line line : lines) {
            String value = fit(font, line.text(), contentWidth);
            text.accept(x + ART_SIZE + 12, lineY, Component.literal(value).withColor(line.colour()));
            lineY += LINE_HEIGHT;
        }
    }

    private record Line(String text, int colour) {}

    private static void drawMascot(GuiGraphicsExtractor extractor, int x, int y,
                                   BotEngine engine, long now) {
        boolean protecting = engine.isEmergencyProtectionActive();
        boolean paused = engine.isPaused();
        int row = protecting || engine.getCurrent() == null ? 2 : paused ? 0 : 1;
        int frameMillis = protecting ? 260 : paused ? 1400 : 480;
        int frame = (int) ((now / frameMillis) % FRAME_COUNT);

        extractor.blit(RenderPipelines.GUI_TEXTURED, MASCOT_BACK, x, y,
                0, 0, ART_SIZE, ART_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE);
        extractor.blit(RenderPipelines.GUI_TEXTURED, MASCOT_SOUL, x, y,
                frame * FRAME_SIZE, row * FRAME_SIZE, ART_SIZE, ART_SIZE,
                FRAME_SIZE, FRAME_SIZE, ATLAS_WIDTH, ATLAS_HEIGHT);
        extractor.blit(RenderPipelines.GUI_TEXTURED, MASCOT_FRONT, x, y,
                0, 0, ART_SIZE, ART_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE, FRAME_SIZE);
    }

    private static String doing(BotEngine engine, DebugInfo debug) {
        if (engine.isEmergencyProtectionActive() && !engine.getEmergencyProtectionStatus().isBlank()) {
            return engine.getEmergencyProtectionStatus();
        }
        if (engine.isPaused()) {
            return "waiting for you to resume";
        }
        if (!debug.intent.isBlank()) {
            return debug.intent;
        }
        if (debug.breakTarget != null) {
            return "breaking " + (debug.breakBlock.isBlank() ? "a block" : debug.breakBlock);
        }
        if (debug.placementTarget != null) {
            return "placing " + (debug.placementBlock.isBlank() ? "a block" : debug.placementBlock);
        }
        if (!debug.taskStatus.isBlank()) {
            return debug.taskStatus;
        }
        Task current = engine.getCurrent();
        return current == null ? "getting ready" : current.name();
    }

    private static String going(DebugInfo debug) {
        if (debug.movementTarget != null) {
            String label = debug.movementLabel.isBlank() ? "route waypoint" : debug.movementLabel;
            return label + " " + position(debug.movementTarget);
        }
        if (debug.targetPos != null) {
            String label = debug.targetLabel.isBlank() ? "target" : debug.targetLabel;
            return label + " " + position(debug.targetPos);
        }
        if (debug.placementTarget != null) {
            return "placement " + position(debug.placementTarget);
        }
        if (debug.breakTarget != null) {
            return "block " + position(debug.breakTarget);
        }
        if (!debug.goal.isBlank() && !"-".equals(debug.goal)) {
            return debug.goal;
        }
        return "staying here";
    }

    private static String position(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String fit(Font font, String value, int maxWidth) {
        if (value == null || value.isBlank() || font.width(value) <= maxWidth) {
            return value == null ? "" : value;
        }
        return font.plainSubstrByWidth(value,
                Math.max(0, maxWidth - font.width(ELLIPSIS)), false) + ELLIPSIS;
    }
}
