package com.etka.lune.client.gui.widget;

import com.etka.lune.bot.catalog.SoundCatalog;
import com.etka.lune.bot.command.Param;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.util.Alerts;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Picks a sound out of the live registry, and plays it as you go.
 *
 * <p>The inline dropdown shows five rows, which is right for a card offering a handful of values
 * and useless against a registry of a thousand-odd sounds. So this is a panel of its own with a
 * search box, in the same shape as the block and item pickers: type to narrow, click to hear,
 * click again to keep.</p>
 *
 * <p><b>Clicking plays it.</b> That is the whole reason this is a panel rather than a longer
 * dropdown. Nobody knows what {@code entity.zombie.ambient} sounds like by reading it, and a card
 * whose sound can only be checked by running the task is a card nobody tunes. The first click on a
 * row previews it and selects it; the second - or the Use button - closes the panel with it
 * chosen.</p>
 *
 * <p>Search runs over both what a sound is called and its id, because the two answer different
 * questions: "zombie" finds it by name, and {@code block.note} finds a family of sounds whose
 * names have nothing in common.</p>
 */
public class SoundPicker extends AbstractWidget {

    private static final int HEADER_H = 22;
    private static final int SEARCH_H = 16;
    private static final int FOOTER_H = 20;
    private static final int ROW_H = 13;
    private static final int PADDING = 8;

    private static final int PANEL_BG = 0xF01A1B20;
    private static final int SEARCH_BG = 0xFF2A2A35;
    private static final int ROW_HOVER = 0xFF2E3442;
    private static final int ROW_SELECTED = 0xFF3E5F86;

    /** Everything on offer, rebuilt when the panel opens because a resource reload can change it. */
    private List<String> pool = List.of();
    /** What the search has left, which is what the rows are drawn from. */
    private final List<String> shown = new ArrayList<>();

    private Param.Choice param;
    private Consumer<String> onChosen;
    private String filter = "";
    private String selected = "";
    private boolean open;
    private int scroll;

    public SoundPicker(int x, int y, int width, int height) {
        super(x, y, width, height, Component.literal(Lang.get("lune.gui.sound.title")));
        visible = false;
        active = false;
    }

    public boolean isOpen() {
        return open;
    }

    public void open(Param.Choice param, Consumer<String> onChosen) {
        this.param = param;
        this.onChosen = onChosen;
        this.selected = param == null ? "" : param.get();
        this.filter = "";
        this.scroll = 0;
        this.pool = SoundCatalog.ids();
        this.open = true;
        visible = true;
        active = true;
        applyFilter();
    }

    public void close() {
        open = false;
        visible = false;
        active = false;
        param = null;
        onChosen = null;
        pool = List.of();
        shown.clear();
    }

    private void choose() {
        Consumer<String> chosen = onChosen;
        String value = selected;
        close();
        if (chosen != null && value != null && !value.isEmpty()) {
            chosen.accept(value);
        }
    }

    private void applyFilter() {
        shown.clear();
        if (filter.isEmpty()) {
            shown.addAll(pool);
        } else {
            String needle = filter.toLowerCase(Locale.ROOT);
            for (String id : pool) {
                if (id.toLowerCase(Locale.ROOT).contains(needle)
                        || SoundCatalog.label(id).toLowerCase(Locale.ROOT).contains(needle)) {
                    shown.add(id);
                }
            }
        }
        scroll = 0;
    }

    private int listTop() {
        return getY() + HEADER_H + SEARCH_H + 2;
    }

    private int listHeight() {
        return Math.max(ROW_H, getHeight() - HEADER_H - SEARCH_H - 2 - FOOTER_H);
    }

    private int visibleRows() {
        return Math.max(1, listHeight() / ROW_H);
    }

    private int maxScroll() {
        return Math.max(0, shown.size() - visibleRows());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // A modal overlay. LuneScreen renders it after the tab widgets so it stays in front.
    }

    public void render(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (!open) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        extractor.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), PANEL_BG);
        extractor.outline(getX(), getY(), getWidth(), getHeight(), LuneScreen.PANEL_BORDER);

        var text = extractor.textRenderer();
        text.accept(getX() + PADDING, getY() + 7,
                Component.literal(Lang.get("lune.gui.sound.title")).withColor(LuneScreen.TEXT));
        text.accept(getX() + getWidth() - 16, getY() + 7,
                Component.literal(Lang.get("lune.gui.inventory.x")).withColor(LuneScreen.TEXT_DIM));

        int searchY = getY() + HEADER_H;
        extractor.fill(getX() + PADDING, searchY, getX() + getWidth() - PADDING, searchY + SEARCH_H,
                SEARCH_BG);
        extractor.fill(getX() + PADDING, searchY + SEARCH_H - 1, getX() + getWidth() - PADDING,
                searchY + SEARCH_H, LuneScreen.ACCENT);
        String typed = filter.isEmpty() ? Lang.get("lune.gui.sound.search_hint") : filter;
        text.accept(getX() + PADDING + 5, searchY + 4, Component.literal(typed)
                .withColor(filter.isEmpty() ? LuneScreen.TEXT_DIM : LuneScreen.TEXT));

        if (shown.isEmpty()) {
            text.accept(getX() + PADDING, listTop() + 4,
                    Component.literal(Lang.get("lune.gui.sound.nothing_matches"))
                            .withColor(LuneScreen.TEXT_DIM));
            drawFooter(extractor, font);
            return;
        }

        extractor.enableScissor(getX(), listTop(), getX() + getWidth(), listTop() + listHeight());
        int rows = Math.min(visibleRows(), shown.size() - scroll);
        for (int row = 0; row < rows; row++) {
            int index = scroll + row;
            String id = shown.get(index);
            int rowY = listTop() + row * ROW_H;
            boolean hovered = mouseX >= getX() + PADDING && mouseX < getX() + getWidth() - PADDING
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean isSelected = id.equals(selected);
            if (isSelected || hovered) {
                extractor.fill(getX() + PADDING, rowY, getX() + getWidth() - PADDING, rowY + ROW_H,
                        isSelected ? ROW_SELECTED : ROW_HOVER);
            }
            String name = SoundCatalog.label(id);
            text.accept(getX() + PADDING + 4, rowY + 3, Component.literal(name)
                    .withColor(isSelected ? LuneScreen.ACCENT : LuneScreen.TEXT));
            // The id beside the name, dimmed. Two sounds can share a subtitle - every note block
            // pitch says the same thing - and then the id is the only way to tell them apart.
            if (!SoundCatalog.SILENT.equals(id)) {
                int idX = getX() + PADDING + 4 + font.width(name) + 6;
                int room = getX() + getWidth() - PADDING - 4 - idX;
                if (room > 20) {
                    text.accept(idX, rowY + 3,
                            Component.literal(clip(font, id, room)).withColor(LuneScreen.TEXT_DIM));
                }
            }
        }
        extractor.disableScissor();
        drawFooter(extractor, font);
    }

    private void drawFooter(GuiGraphicsExtractor extractor, Font font) {
        var text = extractor.textRenderer();
        int footerY = getY() + getHeight() - FOOTER_H + 5;
        text.accept(getX() + PADDING, footerY,
                Component.literal(Lang.get("lune.gui.sound.click_to_hear")).withColor(LuneScreen.TEXT_DIM));
        String use = Lang.get("lune.gui.sound.use");
        text.accept(getX() + getWidth() - PADDING - font.width(use), footerY,
                Component.literal(use).withColor(LuneScreen.ACCENT));
    }

    private static String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        return font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…")), false) + "…";
    }

    /** Clicks arrive in the screen's coordinates, the same ones this was laid out in. */
    public void handleScreenMouseClick(double mouseX, double mouseY, int button) {
        if (!open) {
            return;
        }
        if (mouseX < getX() || mouseX >= getX() + getWidth()
                || mouseY < getY() || mouseY >= getY() + getHeight()) {
            close();
            return;
        }
        if (mouseY < getY() + HEADER_H) {
            close();
            return;
        }
        if (mouseY >= getY() + getHeight() - FOOTER_H) {
            choose();
            return;
        }
        if (mouseY < listTop()) {
            // The search box: clicking it does nothing, since typing already goes here.
            return;
        }
        int row = (int) ((mouseY - listTop()) / ROW_H);
        int index = scroll + row;
        if (row < 0 || index < 0 || index >= shown.size()) {
            return;
        }
        String id = shown.get(index);
        if (id.equals(selected)) {
            // Clicking what is already chosen is how a list says "yes, that one".
            choose();
            return;
        }
        selected = id;
        Alerts.preview(SoundCatalog.sound(id));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        handleScreenMouseClick(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!open) {
            return false;
        }
        scroll = Math.clamp(scroll - (int) Math.signum(scrollY) * 3, 0, maxScroll());
        return true;
    }

    public void handleScreenCharTyped(int codepoint) {
        if (open) {
            filter += Character.toString(codepoint);
            applyFilter();
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!open) {
            return false;
        }
        if (event.isAllowedChatCharacter()) {
            handleScreenCharTyped(event.codepoint());
        }
        return true;
    }

    public void handleScreenKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) {
            return;
        }
        switch (keyCode) {
            case InputConstants.KEY_BACKSPACE -> {
                if (!filter.isEmpty()) {
                    filter = filter.substring(0, filter.length() - 1);
                    applyFilter();
                }
            }
            case InputConstants.KEY_DELETE -> {
                filter = "";
                applyFilter();
            }
            case InputConstants.KEY_ESCAPE -> close();
            case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> choose();
            default -> {
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!open) {
            return false;
        }
        handleScreenKeyPressed(event.key(), 0, event.modifiers());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
