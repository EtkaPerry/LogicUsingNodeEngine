package com.etka.lune.client.gui.widget;

import com.etka.lune.client.gui.Accessibility;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.client.gui.mascot.MascotAdvisor;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.function.Consumer;

/**
 * The Tour page of Lune's corner: the tour's stops in order, and the button that starts it.
 *
 * <p>The panel offers the tour once, the first time it opens, and an offer skipped is not made
 * again. This page is where it is taken after that - from the top, or from any stop, by clicking
 * the stop: a player who only wants to be shown where sharing lives should not have to walk past
 * the dashboard to get there.</p>
 */
public final class TourSection implements LuneMenu.Section {

    private static final int ROW_H = 12;
    private static final int BUTTON_H = 20;
    /** Two columns of stops from this page width up, so the list stays short. */
    private static final int TWO_COLUMNS = 300;

    private final Consumer<PanelTour.Stop> start;

    private record Layout(List<String> intro, int introY, int listY, int columns, int rows,
                          int columnWidth, LuneMenu.Area button) {}

    /** {@code start} is handed the stop to begin at; the corner closes and the tour takes over. */
    public TourSection(Consumer<PanelTour.Stop> start) {
        this.start = start;
    }

    // --- the side menu -------------------------------------------------------

    @Override
    public String title() {
        return Lang.get("lune.tour.title");
    }

    @Override
    public String caption() {
        return Lang.get("lune.tour.caption");
    }

    @Override
    public String luneLine() {
        return Lang.get("lune.mascot.corner.tour");
    }

    @Override
    public MascotAdvisor.Mood luneMood() {
        return MascotAdvisor.Mood.IDLE;
    }

    /** A panel of three rows with the middle one lit: the tour's own picture of what it does. */
    @Override
    public void drawIcon(GuiGraphicsExtractor extractor, int x, int y, int size) {
        int left = x + (size - 16) / 2;
        int top = y + (size - 16) / 2;
        int row = 0xFF5C6272;
        extractor.fill(left + 2, top + 1, left + 14, top + 3, row);
        extractor.fill(left + 2, top + 13, left + 14, top + 15, row);
        MenuPaint.roundedOutline(extractor, left, top + 5, 16, 6, LuneScreen.ACCENT);
        extractor.fill(left + 2, top + 7, left + 14, top + 9, LuneScreen.ACCENT_HOVER);
    }

    // --- the page ------------------------------------------------------------

    private static Layout layout(LuneMenu.Area area, Font font) {
        List<String> intro = MenuPaint.wrap(font, Lang.get("lune.tour.page.intro"), area.width() - 4, 3);
        int introY = area.y() + 24;
        int listY = introY + intro.size() * 10 + 8;
        int count = PanelTour.Stop.values().length;
        int columns = area.width() >= TWO_COLUMNS ? 2 : 1;
        int rows = (count + columns - 1) / columns;
        int columnWidth = (area.width() - 4) / columns;
        String label = Lang.get("lune.tour.page.start");
        int width = MenuPaint.buttonWidth(font, label, MenuPaint.Button.PRIMARY);
        LuneMenu.Area button = new LuneMenu.Area(area.right() - 2 - width,
                Math.max(listY + rows * ROW_H + 8, area.bottom() - 2 - BUTTON_H), width, BUTTON_H);
        return new Layout(intro, introY, listY, columns, rows, columnWidth, button);
    }

    private static LuneMenu.Area row(LuneMenu.Area area, Layout layout, int index) {
        int column = index / layout.rows();
        int line = index % layout.rows();
        return new LuneMenu.Area(area.x() + column * layout.columnWidth(), layout.listY() + line * ROW_H,
                layout.columnWidth() - 4, ROW_H);
    }

    @Override
    public void render(GuiGraphicsExtractor extractor, LuneMenu.Area area, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        Layout layout = layout(area, font);
        MenuPaint.bigText(extractor, title(), area.x() + 2, area.y() + 2, MenuPaint.WHITE);
        for (int i = 0; i < layout.intro().size(); i++) {
            MenuPaint.text(extractor, layout.intro().get(i), area.x() + 2, layout.introY() + i * 10,
                    Accessibility.dim());
        }
        PanelTour.Stop[] stops = PanelTour.Stop.values();
        for (int i = 0; i < stops.length; i++) {
            LuneMenu.Area row = row(area, layout, i);
            boolean hovered = row.contains(mouseX, mouseY);
            if (hovered) {
                MenuPaint.roundedFill(extractor, row.x(), row.y(), row.width(), row.height(), MenuPaint.HOVER);
            }
            String number = String.valueOf(i + 1);
            MenuPaint.text(extractor, number, row.x() + 16 - font.width(number), row.y() + 2,
                    LuneScreen.ACCENT);
            MenuPaint.text(extractor, MenuPaint.clip(font, Lang.get(stops[i].titleKey()), row.width() - 26),
                    row.x() + 22, row.y() + 2, hovered ? MenuPaint.WHITE : LuneScreen.TEXT);
        }
        LuneMenu.Area button = layout.button();
        MenuPaint.button(extractor, font, button.x(), button.y(), button.width(), button.height(),
                Lang.get("lune.tour.page.start"), MenuPaint.Button.PRIMARY,
                button.contains(mouseX, mouseY));
    }

    @Override
    public void click(LuneMenu.Area area, double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        Layout layout = layout(area, Minecraft.getInstance().font);
        if (layout.button().contains(mouseX, mouseY)) {
            MenuPaint.click();
            start.accept(PanelTour.Stop.values()[0]);
            return;
        }
        PanelTour.Stop[] stops = PanelTour.Stop.values();
        for (int i = 0; i < stops.length; i++) {
            if (row(area, layout, i).contains(mouseX, mouseY)) {
                MenuPaint.click();
                start.accept(stops[i]);
                return;
            }
        }
    }

    /** Enter starts it from the top, as the button does. */
    @Override
    public void key(LuneMenu.Area area, int keyCode) {
        if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
            start.accept(PanelTour.Stop.values()[0]);
        }
    }
}
