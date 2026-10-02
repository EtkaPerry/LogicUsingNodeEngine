package com.etka.lune.client.gui;

import com.etka.lune.compat.Screens;
import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.DebugInfo;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.client.gui.mascot.MascotRenderer;
import com.etka.lune.client.gui.mascot.MascotSpeech;
import com.etka.lune.config.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Lune in the corner of the world while a run is on: her face, what she makes of it in the words
 * the panel's bubble would give her, and where the bot is going. F6 remains the full diagnostic
 * overlay.
 *
 * <p>Her mood and her line come from an advisor of the card's own, on the
 * {@link MascotAdvisor.Surface#WORLD} surface, which reads the same state by the same rules as the
 * panel's - the card is her bubble in small, not a second opinion. It is ticked every client tick,
 * screen open or not, so a mood that takes a moment to settle has settled by the time a screen
 * closes.</p>
 */
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
    /**
     * The card while Lune only watches beside the player: narrow enough to sit with the status
     * effects in the corner of somebody's game, because that game is being played.
     */
    private static final int COMPACT_WIDTH = 190;
    /** The head, not the art cell: the card is too tight to spend a third of it on clear pixels. */
    private static final int ART_SIZE = 36;
    private static final int PADDING = 8;
    /** Rows for her line; one more when trouble brings an opening of its own, as in the bubble. */
    private static final int SPEECH_LINES = 2;
    private static final int PROGRESS_BAR_HEIGHT = 3;
    /** Narrower than this and the count says it better than a sliver of bar could. */
    private static final int MIN_PROGRESS_BAR = 24;
    private static final MascotRenderer MASCOT = new MascotRenderer();
    private static final MascotAdvisor ADVISOR = new MascotAdvisor(MascotAdvisor.Surface.WORLD);

    private LuneStatusOverlay() {}

    /** Called once per client tick, after the bot has taken its own. */
    public static void tick() {
        ADVISOR.tick(BotEngine.get());
    }

    public static void render(GuiGraphicsExtractor extractor) {
        Minecraft mc = Minecraft.getInstance();
        BotConfig config = BotConfig.get();
        BotEngine engine = BotEngine.get();
        if (!config.showLune || mc.player == null || mc.level == null || Screens.current(mc) != null
                || engine.isIdle()) {
            return;
        }

        Font font = mc.font;
        // Beside the player and only watching, the card keeps out of the way of somebody who is
        // playing: her face, her line, and the count if the job keeps one. The whole card is back
        // the moment she takes the controls, which is when there is something to follow.
        boolean compact = engine.isWatchingBeside();
        int width = Math.min(compact ? COMPACT_WIDTH : MAX_WIDTH,
                Math.max(1, extractor.guiWidth() - 2 * MARGIN));
        boolean showMascot = width >= 180;
        int textOffset = showMascot ? PADDING + ART_SIZE + 10 : PADDING;
        int contentWidth = Math.max(0, width - textOffset - PADDING);
        int speechRows = SPEECH_LINES;

        DebugInfo debug = engine.getDebug();
        Task current = engine.getCurrent();
        MascotAdvisor.Mood mood = ADVISOR.mood(engine);
        List<Line> lines = new ArrayList<>();
        if (ADVISOR.shouldSpeak(engine)) {
            lines.add(new Line(MascotSpeech.banner(mood), MascotSpeech.colour(mood)));
            if (!compact) {
                addTask(lines, current);
            }
            int rows = MascotSpeech.expanded(mood) ? speechRows + 1 : speechRows;
            for (String said : MascotSpeech.wrap(font, ADVISOR.speech(engine), contentWidth, rows)) {
                lines.add(new Line(said, VALUE));
            }
        } else {
            // Quiet with nothing pressing, or asleep: she has nothing to say, and the card still
            // owes the player what the bot is doing.
            lines.add(new Line(Lang.get("lune.gui.overlay.lune") + state(engine),
                    engine.isPaused() ? PAUSED : compact ? LuneScreen.BESIDE : HEADER));
            if (!compact) {
                addTask(lines, current);
            }
            String doing = doing(engine, debug);
            if (!doing.isBlank()) {
                for (String line : MascotSpeech.wrap(font, doing, contentWidth, speechRows)) {
                    lines.add(new Line(line, VALUE));
                }
            }
        }
        TaskProgress progress = current == null ? null : current.progress();
        if (progress != null) {
            lines.add(new Line("", HEADER, progress));
        }
        String destination = compact ? "" : going(debug);
        if (!destination.isBlank()) {
            lines.add(new Line(destination, LABEL));
        }
        if (!compact && !debug.nextTask.isBlank()) {
            lines.add(new Line(Lang.get("lune.gui.overlay.next", debug.nextTask), LABEL));
        }

        int textHeight = (lines.size() - 1) * LINE_HEIGHT + font.lineHeight;
        int height = Math.max(showMascot ? ART_SIZE : 0, textHeight) + 2 * PADDING;
        int x = extractor.guiWidth() - width - MARGIN;
        int y = MARGIN;

        extractor.fill(x, y, x + width, y + height, BACKGROUND);
        extractor.fill(x, y, x + width, y + 1, BORDER);
        if (showMascot) {
            MASCOT.drawHead(extractor, mood, x + PADDING, y + (height - ART_SIZE) / 2, ART_SIZE,
                    Util.getMillis());
        }
        var text = extractor.textRenderer();
        int lineX = x + textOffset;
        int lineY = y + (height - textHeight) / 2;
        for (Line line : lines) {
            if (line.progress() != null) {
                drawProgress(extractor, font, lineX, lineY, contentWidth, line.progress());
            } else {
                text.accept(lineX, lineY, Component.literal(
                        MascotSpeech.fit(font, line.text(), contentWidth)).withColor(line.colour()));
            }
            lineY += LINE_HEIGHT;
        }
    }

    /** A row of the card: words, or the running task's count and bar when {@code progress} is set. */
    private record Line(String text, int colour, TaskProgress progress) {
        Line(String text, int colour) {
            this(text, colour, null);
        }
    }

    /**
     * Which task this is, as the bubble says it; nothing while the next is still being picked up.
     * Quieter than the line under it, which is the one that changes and the one worth reading.
     */
    private static void addTask(List<Line> lines, Task current) {
        if (current != null) {
            lines.add(new Line(current.name(), LABEL));
        }
    }

    /** The count in the soul's yellow, and the bar across the rest of the row. */
    private static void drawProgress(GuiGraphicsExtractor extractor, Font font, int x, int y,
                                     int width, TaskProgress progress) {
        String label = MascotSpeech.fit(font, progress.label(), width);
        extractor.textRenderer().accept(x, y, Component.literal(label).withColor(HEADER));
        int barX = x + font.width(label) + 6;
        int barWidth = x + width - barX;
        if (barWidth < MIN_PROGRESS_BAR) {
            return;
        }
        // Level with the middle of the lettering, which sits in the top seven rows of the line.
        int barY = y + 2;
        extractor.fill(barX, barY, barX + barWidth, barY + PROGRESS_BAR_HEIGHT, BORDER);
        int filled = Math.round(barWidth * progress.fraction());
        if (filled > 0) {
            extractor.fill(barX, barY, barX + filled, barY + PROGRESS_BAR_HEIGHT, HEADER);
        }
    }

    private static String state(BotEngine engine) {
        return engine.isPaused() ? Lang.get("lune.gui.overlay.paused")
                : engine.getCurrent() == null ? Lang.get("lune.gui.overlay.starting")
                : engine.isWatchingBeside() ? Lang.get("lune.beside.state")
                : Lang.get("lune.gui.overlay.working");
    }

    /** What the bot is doing, or nothing when the task's name above already says all there is. */
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
        return active == null ? Lang.get("lune.gui.overlay.getting_ready") : "";
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
}
