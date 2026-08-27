package com.etka.lune.client.gui.tab;

import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.DebugInfo;
import com.etka.lune.bot.Task;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.LuneTab;
import com.etka.lune.client.gui.widget.ListPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Live dashboard for the bot. The Routines tab is the editor; Main is deliberately focused on
 * answering three questions quickly: what is running, what is the player doing, and what comes
 * next.
 */
public class MainTab extends LuneTab {

    private static final int CONTROL_H = DashboardFrame.CONTROL_H;
    private static final int TITLE_H = DashboardFrame.TITLE_H;
    private static final int ROW_H = DashboardFrame.ROW_H;
    private static final int BODY_TOP = DashboardFrame.BODY_TOP;
    private static final int BUTTON_GAP = DashboardFrame.BUTTON_GAP;
    private static final int PANEL_HEADER = 0xD0222D3A;
    private static final int RUNNING = 0xFF69E391;
    private static final int PAUSED = 0xFFFFC15C;
    private static final int IDLE = 0xFF9299A6;

    private final Button pauseButton;
    private final Button clearButton;
    private final Button stopButton;
    private final ListPanel<Task> queueList;

    public MainTab() {
        super(Component.literal("Main"));

        pauseButton = add(Button.builder(Component.literal("Pause"), b -> togglePause())
                .size(76, CONTROL_H).build());
        clearButton = add(Button.builder(Component.literal("Clear queue"), b -> BotEngine.get().clearQueue())
                .size(92, CONTROL_H).build());
        stopButton = add(Button.builder(Component.literal("Stop all"), b -> BotEngine.get().stopAll())
                .size(76, CONTROL_H).build());
        queueList = add(new ListPanel<>(0, 0, 10, 10, Task::name, task -> {}));
    }

    private void togglePause() {
        BotEngine engine = BotEngine.get();
        engine.setPaused(!engine.isPaused());
    }

    @Override
    public void tick() {
        BotEngine engine = BotEngine.get();
        pauseButton.setMessage(Component.literal(engine.isPaused() ? "Resume" : "Pause"));
        pauseButton.active = engine.getCurrent() != null;
        clearButton.active = !engine.getQueue().isEmpty();
        stopButton.active = engine.getCurrent() != null || !engine.getQueue().isEmpty();
        queueList.setItems(engine.getQueue());
    }

    private static DashboardFrame frameOf(ScreenRectangle area) {
        return DashboardFrame.of(area.left(), area.top(), area.right(), area.bottom());
    }

    @Override
    protected void layout(ScreenRectangle area) {
        DashboardFrame frame = frameOf(area);
        queueList.setPosition(frame.left() + 8, frame.queueTop() + TITLE_H + 6);
        queueList.setSize(Math.max(40, frame.width() - 16),
                Math.max(20, frame.queueHeight() - TITLE_H - 14));

        // Sized every pass, not just when narrow: a screen that grows again has to undo this.
        if (frame.sharedButtonRow()) {
            int buttonWidth = Math.max(28, (frame.width() - BUTTON_GAP * 2) / 3);
            place(pauseButton, frame.left(), frame.buttonY(), buttonWidth);
            place(clearButton, frame.left() + buttonWidth + BUTTON_GAP, frame.buttonY(), buttonWidth);
            place(stopButton, frame.left() + (buttonWidth + BUTTON_GAP) * 2, frame.buttonY(), buttonWidth);
        } else {
            place(pauseButton, frame.left(), frame.buttonY(), 76);
            place(clearButton, frame.left() + 82, frame.buttonY(), 92);
            place(stopButton, frame.left() + 182, frame.buttonY(), 76);
        }
    }

    private static void place(Button button, int x, int y, int width) {
        button.setPosition(x, y);
        button.setSize(width, CONTROL_H);
    }

    @Override
    public void extractTabBackground(GuiGraphicsExtractor extractor) {
        DashboardFrame frame = frameOf(area);
        panel(extractor, frame.status());
        if (frame.telemetry() != null) {
            panel(extractor, frame.telemetry());
        }
        panel(extractor, frame.left(), frame.queueTop(), frame.width(), frame.queueHeight());
    }

    @Override
    public void extractTabRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                      float partialTick) {
        BotEngine engine = BotEngine.get();
        DebugInfo debug = engine.getDebug();
        Task current = engine.getCurrent();
        Minecraft mc = Minecraft.getInstance();

        DashboardFrame frame = frameOf(area);
        drawStatusCard(extractor, frame.status(), engine, current, debug);
        if (frame.telemetry() != null) {
            drawTelemetryCard(extractor, frame.telemetry(), engine, debug, mc);
        }
        drawQueueHeader(extractor, frame.left(), frame.queueTop(), frame.width(), engine);
        extractor.textRenderer().accept(frame.left(), frame.hintY(),
                Component.literal(fit("K  Pause / resume     Shift+K  Stop everything", frame.width()))
                        .withColor(LuneScreen.TEXT_DIM));
    }

    /**
     * A line in a card. {@code priority} is the order the lines are given up in as the card gets
     * shorter, which is not the order they are drawn in - Goal survives longer than Facing.
     */
    private record Row(int priority, String label, String value, int colour) {}

    private void drawStatusCard(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                BotEngine engine, Task current, DebugInfo debug) {
        drawPanelHeader(extractor, card, "BOT STATUS");
        int stateColour = stateColour(engine, current);
        String state = engine.describeState().toUpperCase();
        int stateWidth = Math.min(card.width() - 20,
                Math.max(62, Minecraft.getInstance().font.width(state) + 18));
        int stateX = card.x() + card.width() - stateWidth - 8;
        extractor.fill(stateX, card.y() + 5, stateX + stateWidth, card.y() + 17,
                0x40202028 | (stateColour & 0x00FFFFFF));
        extractor.textRenderer().accept(stateX + 9, card.y() + 7,
                Component.literal(state).withColor(stateColour));

        String event = engine.getLastMessage();
        drawRows(extractor, card, 62, List.of(
                new Row(1, "Current", current == null ? "Nothing is running" : current.name(),
                        LuneScreen.TEXT),
                new Row(2, "", current == null ? "Ready for a task"
                        : firstNonBlank(current.status(), debug.taskStatus, "Working"), stateColour),
                current instanceof com.etka.lune.bot.task.RoutineTask routine
                        ? new Row(3, "Flow", routine.describeFlow(), LuneScreen.ACCENT)
                        : new Row(3, "Queue next",
                                debug.nextTask.isBlank() ? "Nothing queued" : debug.nextTask,
                                LuneScreen.TEXT),
                new Row(4, "Last", event.isBlank() ? "No completed actions yet" : event,
                        LuneScreen.TEXT_DIM)));
    }

    /** Draws the rows a card is tall enough for, in the order given. */
    private static void drawRows(GuiGraphicsExtractor extractor, DashboardFrame.Card card, int labelWidth,
                                 List<Row> rows) {
        var text = extractor.textRenderer();
        int textX = card.x() + 10;
        int y = card.y() + TITLE_H + BODY_TOP;
        for (Row row : rows) {
            if (row.priority() > card.rows()) {
                continue;
            }
            if (row.label().isEmpty()) {
                text.accept(textX, y, Component.literal(fit(row.value(), card.width() - 20))
                        .withColor(row.colour()));
            } else {
                text.accept(textX, y, Component.literal(row.label()).withColor(LuneScreen.TEXT_DIM));
                text.accept(textX + labelWidth, y,
                        Component.literal(fit(row.value(), card.width() - labelWidth - 20))
                                .withColor(row.colour()));
            }
            y += ROW_H;
        }
    }

    private static int stateColour(BotEngine engine, Task current) {
        if (current == null) {
            return IDLE;
        }
        return engine.isPaused() ? PAUSED : RUNNING;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private void drawTelemetryCard(GuiGraphicsExtractor extractor, DashboardFrame.Card card,
                                   BotEngine engine, DebugInfo debug, Minecraft mc) {
        drawPanelHeader(extractor, card, "PLAYER / WORLD");
        if (mc.player == null || mc.level == null) {
            drawRows(extractor, card, 64, List.of(
                    new Row(1, "", "Not in a world", LuneScreen.TEXT_DIM),
                    new Row(2, "", "Open a world to see live telemetry", LuneScreen.TEXT_DIM)));
            return;
        }

        var feet = mc.player.blockPosition();
        String dimension = mc.level.dimension().identifier().toString();
        drawRows(extractor, card, 64, List.of(
                new Row(1, "Position", feet.getX() + "  " + feet.getY() + "  " + feet.getZ(),
                        LuneScreen.TEXT),
                new Row(2, "Health", formatOne(mc.player.getHealth()) + " / "
                        + formatOne(mc.player.getMaxHealth())
                        + "   Food " + mc.player.getFoodData().getFoodLevel(), LuneScreen.TEXT),
                new Row(4, "Facing",
                        Mth.floor(Mth.wrapDegrees(mc.player.getYRot())) + "°   " + dimension,
                        LuneScreen.TEXT),
                new Row(5, "Input", debug.keys == null || debug.keys.isBlank() ? "-" : debug.keys,
                        engine.isDriving() ? RUNNING : LuneScreen.TEXT_DIM),
                new Row(3, "Goal", debug.goal, LuneScreen.TEXT)));
    }

    private void drawQueueHeader(GuiGraphicsExtractor extractor, int x, int y, int width,
                                 BotEngine engine) {
        drawPanelHeader(extractor, x, y, width, "UP NEXT");
        var text = extractor.textRenderer();
        var font = Minecraft.getInstance().font;
        int count = engine.getQueue().size();
        String subtitle = count == 0 ? "Queue is empty" : count + (count == 1 ? " task waiting" : " tasks waiting");
        // Only when it clears the title; on a narrow card the two would otherwise print over each other.
        if (font.width("UP NEXT") + font.width(subtitle) + 28 <= width) {
            text.accept(x + width - 10 - font.width(subtitle), y + 6,
                    Component.literal(subtitle).withColor(LuneScreen.TEXT_DIM));
        }
        if (count == 0) {
            text.accept(x + 10, y + TITLE_H + BODY_TOP,
                    Component.literal(fit("Run a task from the Task tab to start the bot.", width - 20))
                            .withColor(LuneScreen.TEXT_DIM));
        }
    }

    private void drawPanelHeader(GuiGraphicsExtractor extractor, DashboardFrame.Card card, String title) {
        drawPanelHeader(extractor, card.x(), card.y(), card.width(), title);
    }

    private void drawPanelHeader(GuiGraphicsExtractor extractor, int x, int y, int width, String title) {
        extractor.fill(x + 1, y + 1, x + width - 1, y + TITLE_H, PANEL_HEADER);
        extractor.textRenderer().accept(x + 9, y + 5,
                Component.literal(title).withColor(LuneScreen.ACCENT));
    }

    private static String formatOne(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String fit(String value, int maxWidth) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        var font = Minecraft.getInstance().font;
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String suffix = "…";
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(suffix)), false) + suffix;
    }

    private static void panel(GuiGraphicsExtractor extractor, int x, int y, int width, int height) {
        LuneScreen.panel(extractor, x, y, width, height);
    }

    private static void panel(GuiGraphicsExtractor extractor, DashboardFrame.Card card) {
        LuneScreen.panel(extractor, card.x(), card.y(), card.width(), card.height());
    }
}
