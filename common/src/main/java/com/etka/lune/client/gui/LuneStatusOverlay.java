package com.etka.lune.client.gui;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.DebugInfo;
import com.etka.lune.bot.Task;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.client.gui.mascot.MascotRenderer;
import com.etka.lune.config.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
    private static final int MAX_WIDTH = 300;
    private static final int ART_SIZE = 36;
    private static final int PADDING = 8;
    private static final MascotRenderer MASCOT = new MascotRenderer();
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
        int headerColour = engine.isPaused() ? PAUSED : HEADER;
        String state = engine.isPaused() ? Lang.get("lune.gui.overlay.paused")
                : engine.getCurrent() == null ? Lang.get("lune.gui.overlay.starting") : Lang.get("lune.gui.overlay.working");
        lines.add(new Line(Lang.get("lune.gui.overlay.lune") + state, headerColour));
        lines.add(new Line(doing(engine, debug), VALUE));
        String destination = going(debug);
        if (!destination.isBlank()) {
            lines.add(new Line(destination, LABEL));
        }
        if (!debug.nextTask.isBlank()) {
            lines.add(new Line(Lang.get("lune.gui.overlay.next", debug.nextTask), LABEL));
        }

        Font font = mc.font;
        int width = Math.min(MAX_WIDTH, Math.max(1, extractor.guiWidth() - 2 * MARGIN));
        boolean showMascot = width >= 180;
        int textOffset = showMascot ? PADDING + ART_SIZE + 10 : PADDING;
        int contentWidth = Math.max(0, width - textOffset - PADDING);
        int textHeight = (lines.size() - 1) * LINE_HEIGHT + font.lineHeight;
        int height = Math.max(showMascot ? ART_SIZE : 0, textHeight) + 2 * PADDING;
        int x = extractor.guiWidth() - width - MARGIN;
        int y = MARGIN;

        extractor.fill(x, y, x + width, y + height, BACKGROUND);
        extractor.fill(x, y, x + width, y + 1, BORDER);
        int mascotY = y + (height - ART_SIZE) / 2;
        if (showMascot) {
            MascotAdvisor.Mood mood = engine.isPaused() ? MascotAdvisor.Mood.PAUSED
                    : engine.getCurrent() == null ? MascotAdvisor.Mood.THINKING
                    : MascotAdvisor.Mood.WORKING;
            MASCOT.draw(extractor, mood, x + PADDING, mascotY, ART_SIZE, Util.getMillis());
        }
        var text = extractor.textRenderer();
        int lineY = y + (height - textHeight) / 2;
        for (Line line : lines) {
            String value = fit(font, line.text(), contentWidth);
            text.accept(x + textOffset, lineY, Component.literal(value).withColor(line.colour()));
            lineY += LINE_HEIGHT;
        }
    }

    private record Line(String text, int colour) {}

    private static String doing(BotEngine engine, DebugInfo debug) {
        if (engine.isPaused()) {
            return Lang.get("lune.gui.overlay.waiting_resume");
        }
        Task active = engine.getCurrent();
        if (active != null && !active.status().isBlank()) {
            return active.status();
        }
        if (debug.breakTarget != null) {
            return Lang.get("lune.gui.overlay.breaking", debug.breakBlock.isBlank() ? Lang.get("lune.gui.overlay.a_block") : debug.breakBlock);
        }
        if (debug.placementTarget != null) {
            return Lang.get("lune.gui.overlay.placing", debug.placementBlock.isBlank() ? Lang.get("lune.gui.overlay.a_block") : debug.placementBlock);
        }
        if (!debug.taskStatus.isBlank()) {
            return debug.taskStatus;
        }
        Task current = engine.getCurrent();
        return current == null ? Lang.get("lune.gui.overlay.getting_ready") : current.name();
    }

    private static String going(DebugInfo debug) {
        if (debug.movementTarget != null) {
            String label = Lang.get("lune.gui.overlay.route_waypoint");
            return label + " " + position(debug.movementTarget);
        }
        if (debug.targetPos != null) {
            String label = Lang.get("lune.gui.overlay.target");
            return label + " " + position(debug.targetPos);
        }
        if (debug.placementTarget != null) {
            return Lang.get("lune.gui.overlay.placement", position(debug.placementTarget));
        }
        if (debug.breakTarget != null) {
            return Lang.get("lune.gui.overlay.block", position(debug.breakTarget));
        }
        return "";
    }

    private static String position(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static String fit(Font font, String value, int maxWidth) {
        if (maxWidth < font.width(ELLIPSIS)) {
            return "";
        }
        if (value == null || value.isBlank() || font.width(value) <= maxWidth) {
            return value == null ? "" : value;
        }
        return font.plainSubstrByWidth(value,
                Math.max(0, maxWidth - font.width(ELLIPSIS)), false) + ELLIPSIS;
    }
}
