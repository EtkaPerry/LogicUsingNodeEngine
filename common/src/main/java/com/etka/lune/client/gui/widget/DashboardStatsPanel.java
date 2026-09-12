package com.etka.lune.client.gui.widget;

import com.etka.lune.util.Lang;
import com.etka.lune.client.gui.LuneScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A compact, scrollable statistics view for the Main dashboard.
 *
 * <p>The dashboard has far more useful counters than can fit in one card. This widget owns the
 * scroll position, while MainTab only supplies the selected scope's current lines.</p>
 */
public final class DashboardStatsPanel extends AbstractWidget {

    public record Line(String label, String value, int colour, boolean section) {
        public static Line metric(String label, String value, int colour) {
            return new Line(label, value, colour, false);
        }

        public static Line section(String label) {
            return new Line(label, "", LuneScreen.ACCENT, true);
        }
    }

    private static final int PADDING = 4;
    private static final int DEFAULT_ROW_HEIGHT = 15;
    private static final int SCROLL_TRACK = 0x503C4654;
    private static final int SCROLL_THUMB = 0xC06D82A0;

    private List<Line> lines = List.of();
    private int scrollRows;
    private int rowHeight = DEFAULT_ROW_HEIGHT;

    public DashboardStatsPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    public void setLines(List<Line> newLines) {
        lines = newLines == null ? List.of() : List.copyOf(newLines);
        clampScroll();
    }

    public void setLineHeight(int lineHeight) {
        rowHeight = Math.clamp(lineHeight, 12, 28);
        clampScroll();
    }

    private int visibleRows() {
        return Math.max(1, (getHeight() - PADDING * 2) / rowHeight);
    }

    private void clampScroll() {
        scrollRows = Math.clamp(scrollRows, 0, Math.max(0, lines.size() - visibleRows()));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        clampScroll();
        var text = extractor.textRenderer();
        int visible = visibleRows();
        int valueX = getX() + Math.min(86, Math.max(42, getWidth() / 3));
        for (int row = 0; row < visible; row++) {
            int index = scrollRows + row;
            if (index >= lines.size()) {
                break;
            }
            Line line = lines.get(index);
            int y = getY() + PADDING + row * rowHeight;
            if (line.section()) {
                text.accept(getX() + PADDING, y,
                        Component.literal(fit(line.label(), Math.max(1, getWidth() - PADDING * 2)))
                                .withColor(line.colour()));
                continue;
            }
            text.accept(getX() + PADDING, y,
                    Component.literal(fit(line.label(), Math.max(1, valueX - getX() - PADDING - 4)))
                            .withColor(LuneScreen.TEXT_DIM));
            text.accept(valueX, y,
                    Component.literal(fit(line.value(), Math.max(1, getX() + getWidth() - valueX - 7)))
                            .withColor(line.colour()));
        }

        if (lines.size() > visible) {
            int trackTop = getY() + PADDING;
            int trackBottom = getY() + getHeight() - PADDING;
            int trackHeight = Math.max(1, trackBottom - trackTop);
            int thumbHeight = Math.max(8, trackHeight * visible / lines.size());
            int travel = Math.max(0, trackHeight - thumbHeight);
            int maxScroll = Math.max(1, lines.size() - visible);
            int thumbTop = trackTop + travel * scrollRows / maxScroll;
            extractor.fill(getX() + getWidth() - 3, trackTop,
                    getX() + getWidth() - 1, trackBottom, SCROLL_TRACK);
            extractor.fill(getX() + getWidth() - 3, thumbTop,
                    getX() + getWidth() - 1, thumbTop + thumbHeight, SCROLL_THUMB);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!isMouseOver(mouseX, mouseY) || lines.size() <= visibleRows()) {
            return false;
        }
        scrollRows -= (int) Math.signum(scrollY);
        clampScroll();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.literal(Lang.get("lune.gui.dashboard_stats.statistics")));
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
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width(suffix)), false)
                + suffix;
    }
}
