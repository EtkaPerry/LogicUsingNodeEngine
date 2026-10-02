package com.etka.lune.client.gui.widget;

import com.etka.lune.Constants;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.compat.Screens;
import com.etka.lune.config.SharePolicy;
import com.etka.lune.share.ShareLedger;
import com.etka.lune.share.ShareService;
import com.etka.lune.task.TaskCode;
import com.etka.lune.task.TaskGraph;
import com.etka.lune.task.TaskStore;
import com.etka.lune.util.Lang;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Tasks tab's Share popup: what sharing does, who may change the task, then the link.
 *
 * <p>Nothing leaves the game until Create link is pressed, and the popup says what that means
 * before it is: the task goes to the share service, anyone holding the link can read it -
 * coordinates, waypoint names and notes included - and a link nobody opens for thirty days is
 * deleted. The player picks <b>View only</b> (the default) or <b>View and edit</b>; an edit link
 * lets whoever has it change the task behind that same link, which the player then brings back
 * with Import. The first link also asks for agreement to the share policy ({@link SharePolicy}),
 * on the button itself, beside a button that opens it.</p>
 *
 * <p>When the service cannot be reached, or refuses, the link carries the whole task in it
 * instead. It is longer, needs nothing kept anywhere and can only be viewed, so Share always ends
 * with a link that opens the same page; the popup says which kind it handed over. Copy as text is
 * the old Export, for a player who would rather paste the task somewhere themselves.</p>
 *
 * <p>Drawn and routed by {@code LuneScreen} like {@link NamePrompt}: while it is up it owns the
 * pointer and the keyboard.</p>
 */
public class SharePrompt extends AbstractWidget {

    private enum Stage {
        /** What sharing does, the choice of view only or view and edit, and plain text. */
        CHOOSE,
        UPLOADING,
        /** A short link to the task the service now keeps. */
        KEPT,
        /** A long link with the task inside it, because the service did not keep it. */
        CARRIED
    }

    private enum Action {
        VIEW_ONLY, VIEW_AND_EDIT, POLICY, CREATE, COPY_TEXT, CANCEL, OPEN, COPY_LINK, DELETE, CLOSE
    }

    private record Hit(Action action, int x, int y, int width, String label) {}

    private static final int PADDING = 10;
    private static final int POPUP_W = 340;
    private static final int LINE_H = 10;
    private static final int TITLE_GAP = 6;
    private static final int LINK_H = 16;
    private static final int BUTTON_H = 18;
    private static final int BUTTON_GAP = 6;
    private static final int BUTTON_PAD = 14;
    private static final int BUTTON_MIN_W = 48;
    private static final int SECTION_GAP = 8;
    private static final int DIM = 0xB0000000;
    private static final int LINK_BG = 0xFF2A2A35;
    private static final int PRIMARY = LuneScreen.ACCENT;

    private Stage stage = Stage.CHOOSE;
    private TaskGraph task;
    /** Whether the link lets whoever has it change the task. View only unless the player says. */
    private boolean editable;
    private String code = "";
    private String link = "";
    private ShareService.Shared shared;
    /** A short line under the link: copied again, deleting, could not delete. */
    private String status = "";
    private Consumer<String> onMessage = text -> {};
    /** Which upload or delete a late answer belongs to; a closed or reopened popup ignores it. */
    private int attempt;

    public SharePrompt() {
        super(-1000, -1000, 10, 10, Component.literal(Lang.get("lune.gui.tasks.share")));
        this.visible = false;
        this.active = false;
    }

    public boolean isOpen() {
        return visible;
    }

    /** Opens the popup for one task; {@code onMessage} is the Tasks tab's status line. */
    public void open(TaskGraph task, Consumer<String> onMessage) {
        this.task = task;
        this.onMessage = onMessage == null ? text -> {} : onMessage;
        this.stage = Stage.CHOOSE;
        this.editable = false;
        this.code = "";
        this.link = "";
        this.shared = null;
        this.status = "";
        this.attempt++;
        this.visible = true;
        this.active = true;
        setFocused(true);
    }

    public void close() {
        visible = false;
        active = false;
        setFocused(false);
        setPosition(-1000, -1000);
        task = null;
        attempt++;
    }

    @Override
    public void setPosition(int x, int y) {
        setX(x);
        setY(y);
    }

    // --- what the buttons do -------------------------------------------------------------------

    private void run(Action action) {
        switch (action) {
            case VIEW_ONLY -> editable = false;
            case VIEW_AND_EDIT -> editable = true;
            case POLICY -> openLink(ShareService.standard().base() + "/policy");
            case CREATE -> create();
            case COPY_TEXT -> copyAsText();
            case OPEN -> openLink(link);
            case COPY_LINK -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(link);
                status = Lang.get("lune.gui.share.copied");
            }
            case DELETE -> delete();
            case CANCEL, CLOSE -> close();
        }
    }

    private void create() {
        if (task == null) {
            return;
        }
        if (!SharePolicy.accepted()) {
            // The button said "Agree and create link"; pressing it is the agreement.
            SharePolicy.accept();
        }
        code = TaskCode.encode(task);
        stage = Stage.UPLOADING;
        status = "";
        int mine = ++attempt;
        boolean wantEdit = editable;
        ShareService service = ShareService.standard();
        service.upload(code, wantEdit).whenComplete((made, failure) -> Minecraft.getInstance().execute(() -> {
            if (mine != attempt || !visible) {
                return;
            }
            if (failure == null) {
                shared = made;
                link = made.link();
                stage = Stage.KEPT;
                ShareLedger.remember(made, task == null ? "" : task.name);
            } else {
                Constants.LOG.info("Share service did not keep the task ({}); handing over a link "
                        + "that carries it", ShareService.cause(failure).toString());
                link = service.linkCarrying(code);
                stage = Stage.CARRIED;
            }
            // Copied straight away: a link is made to be pasted, and the popup says it was.
            Minecraft.getInstance().keyboardHandler.setClipboard(link);
        }));
    }

    private void copyAsText() {
        if (task != null && TaskStore.get().exportToClipboard(task)) {
            onMessage.accept(Lang.get("lune.gui.tasks.copied_clipboard", task.displayName()));
        }
        close();
    }

    private static void openLink(String address) {
        // The game's own "open this link?" screen, which every version has. It hands back to the
        // panel when it closes, and this popup is still up with the same link on it.
        Minecraft mc = Minecraft.getInstance();
        ConfirmLinkScreen.confirmLinkNow(Screens.current(mc), URI.create(address));
    }

    private void delete() {
        if (shared == null) {
            return;
        }
        ShareService.Shared doomed = shared;
        status = Lang.get("lune.gui.share.deleting");
        int mine = ++attempt;
        ShareService.standard().delete(doomed.id(), doomed.deleteKey())
                .whenComplete((ignored, failure) -> Minecraft.getInstance().execute(() -> {
                    if (failure == null) {
                        ShareLedger.forget(doomed.id());
                        onMessage.accept(Lang.get("lune.gui.share.deleted"));
                        if (mine == attempt && visible) {
                            close();
                        }
                    } else if (mine == attempt && visible) {
                        status = Lang.get("lune.gui.share.delete_failed");
                    }
                }));
    }

    // --- what it says ----------------------------------------------------------------------------

    private String title() {
        return Lang.get("lune.gui.share.title", task == null ? "" : task.displayName());
    }

    private String body() {
        String host = ShareService.standard().host();
        return switch (stage) {
            case CHOOSE -> Lang.get("lune.gui.share.about", host);
            case UPLOADING -> Lang.get("lune.gui.share.uploading");
            case KEPT -> Lang.get(shared != null && shared.editable()
                    ? "lune.gui.share.kept_edit" : "lune.gui.share.kept_view");
            case CARRIED -> Lang.get("lune.gui.share.carried", host);
        };
    }

    /** Under the two mode buttons: what the one chosen lets other people do. */
    private String modeAbout() {
        return Lang.get(editable ? "lune.gui.share.view_edit_about" : "lune.gui.share.view_only_about");
    }

    private List<Action> actions() {
        return switch (stage) {
            case CHOOSE -> List.of(Action.POLICY, Action.COPY_TEXT, Action.CANCEL, Action.CREATE);
            case UPLOADING -> List.of(Action.CANCEL);
            case KEPT -> List.of(Action.DELETE, Action.OPEN, Action.COPY_LINK, Action.CLOSE);
            case CARRIED -> List.of(Action.OPEN, Action.COPY_LINK, Action.CLOSE);
        };
    }

    /** What Enter does. */
    private Action primaryAction() {
        return switch (stage) {
            case CHOOSE -> Action.CREATE;
            case UPLOADING -> null;
            case KEPT, CARRIED -> Action.COPY_LINK;
        };
    }

    /** Delete and Policy start from the left: apart from the buttons that move things on. */
    private static boolean leftAligned(Action action) {
        return action == Action.DELETE || action == Action.POLICY;
    }

    private static String label(Action action) {
        return Lang.get(switch (action) {
            case VIEW_ONLY -> "lune.gui.share.view_only";
            case VIEW_AND_EDIT -> "lune.gui.share.view_edit";
            case POLICY -> "lune.gui.share.policy";
            case CREATE -> SharePolicy.accepted() ? "lune.gui.share.create" : "lune.gui.share.agree_create";
            case COPY_TEXT -> "lune.gui.share.copy_text";
            case CANCEL -> "lune.gui.name_prompt.cancel";
            case OPEN -> "lune.gui.share.open";
            case COPY_LINK -> "lune.gui.share.copy_link";
            case DELETE -> "lune.gui.tasks.delete_2";
            case CLOSE -> "lune.gui.recipe.close";
        });
    }

    // --- drawing --------------------------------------------------------------------------------

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        // Required by AbstractWidget; drawn through render() so LuneScreen can order it on top.
    }

    public void render(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Layout layout = layout(font);
        extractor.fill(0, 0, Screens.current(mc).width, Screens.current(mc).height, DIM);
        LuneScreen.panel(extractor, layout.x, layout.y, layout.width, layout.height);

        var text = extractor.textRenderer();
        int inner = layout.width - PADDING * 2;
        int left = layout.x + PADDING;
        text.accept(left, layout.y + PADDING, Component.literal(clip(font, title(), inner))
                .withColor(LuneScreen.TEXT));
        int y = layout.y + PADDING + LINE_H + TITLE_GAP;
        for (String line : layout.body) {
            text.accept(left, y, Component.literal(line).withColor(LuneScreen.TEXT_DIM));
            y += LINE_H;
        }
        if (stage == Stage.CHOOSE) {
            int modeY = layout.modeY;
            for (int i = 0; i < layout.modeAbout.size(); i++) {
                text.accept(left, modeY + BUTTON_H + 5 + i * LINE_H,
                        Component.literal(layout.modeAbout.get(i)).withColor(LuneScreen.TEXT));
            }
            int policyY = modeY + BUTTON_H + 5 + layout.modeAbout.size() * LINE_H + SECTION_GAP;
            for (int i = 0; i < layout.policy.size(); i++) {
                text.accept(left, policyY + i * LINE_H, Component.literal(layout.policy.get(i))
                        .withColor(LuneScreen.TEXT_DIM));
            }
        }
        if (!link.isEmpty()) {
            int linkY = layout.linkY;
            extractor.fill(left, linkY, layout.x + layout.width - PADDING, linkY + LINK_H, LINK_BG);
            text.accept(left + 5, linkY + 4, Component.literal(clip(font, link, inner - 10))
                    .withColor(LuneScreen.ACCENT_HOVER));
            if (!status.isEmpty()) {
                text.accept(left, linkY + LINK_H + 4, Component.literal(clip(font, status, inner))
                        .withColor(LuneScreen.TEXT));
            }
        }
        for (Hit hit : layout.buttons) {
            boolean chosen = hit.action() == Action.VIEW_ONLY && !editable
                    || hit.action() == Action.VIEW_AND_EDIT && editable;
            boolean primary = hit.action() == primaryAction() || chosen;
            boolean hovered = mouseX >= hit.x() && mouseX < hit.x() + hit.width()
                    && mouseY >= hit.y() && mouseY < hit.y() + BUTTON_H;
            int fill = hovered ? LuneScreen.ACCENT_HOVER : primary ? PRIMARY : LuneScreen.PANEL_BG;
            extractor.fill(hit.x(), hit.y(), hit.x() + hit.width(), hit.y() + BUTTON_H, fill);
            extractor.fill(hit.x(), hit.y(), hit.x() + hit.width(), hit.y() + 1, LuneScreen.PANEL_BORDER);
            extractor.fill(hit.x(), hit.y() + BUTTON_H - 1, hit.x() + hit.width(), hit.y() + BUTTON_H,
                    LuneScreen.PANEL_BORDER);
            text.accept(hit.x() + (hit.width() - font.width(hit.label())) / 2, hit.y() + 5,
                    Component.literal(hit.label()).withColor(hovered || primary ? 0xFF000000 : LuneScreen.TEXT));
        }
    }

    private record Layout(int x, int y, int width, int height, List<String> body, int modeY,
                          List<String> modeAbout, List<String> policy, int linkY, List<Hit> buttons) {}

    /**
     * Where everything goes this frame. Measured from the words rather than fixed, because the
     * buttons and the paragraphs are a different length in every language: the popup is as wide
     * as its buttons need, and never narrower than {@link #POPUP_W}.
     */
    private Layout layout(Font font) {
        Minecraft mc = Minecraft.getInstance();
        List<Action> actions = actions();
        int[] widths = new int[actions.size()];
        int needed = PADDING * 2;
        for (int i = 0; i < actions.size(); i++) {
            widths[i] = Math.max(BUTTON_MIN_W, font.width(label(actions.get(i))) + BUTTON_PAD);
            // The left-hand buttons keep a clear stretch between themselves and the rest.
            needed += widths[i] + (leftAligned(actions.get(i)) ? BUTTON_GAP * 4 : BUTTON_GAP);
        }
        int width = Math.min(Math.max(POPUP_W, needed), Screens.current(mc).width - 8);
        int inner = width - PADDING * 2;
        List<String> body = wrap(font, body(), inner);
        List<String> modeAbout = stage == Stage.CHOOSE ? wrap(font, modeAbout(), inner) : List.of();
        List<String> policy = stage == Stage.CHOOSE ? wrap(font, Lang.get("lune.gui.share.policy_line"), inner)
                : List.of();
        int height = PADDING + LINE_H + TITLE_GAP + body.size() * LINE_H;
        int modeY = 0;
        if (stage == Stage.CHOOSE) {
            modeY = height + SECTION_GAP;
            // Two buttons, the lines under them, then the policy line.
            height = modeY + BUTTON_H + 5 + modeAbout.size() * LINE_H + SECTION_GAP + policy.size() * LINE_H;
        }
        int linkY = 0;
        if (!link.isEmpty()) {
            linkY = height + 4;
            height = linkY + LINK_H + (status.isEmpty() ? 0 : 4 + LINE_H);
        }
        height += 10 + BUTTON_H + PADDING;
        int x = (Screens.current(mc).width - width) / 2;
        int y = (Screens.current(mc).height - height) / 2;
        int buttonY = y + height - PADDING - BUTTON_H;
        List<Hit> buttons = new ArrayList<>();
        if (stage == Stage.CHOOSE) {
            int modeX = x + PADDING;
            for (Action mode : List.of(Action.VIEW_ONLY, Action.VIEW_AND_EDIT)) {
                String label = label(mode);
                int modeWidth = Math.max(BUTTON_MIN_W, font.width(label) + BUTTON_PAD);
                buttons.add(new Hit(mode, modeX, y + modeY, modeWidth, label));
                modeX += modeWidth + BUTTON_GAP;
            }
        }
        // Right to left from the corner, so the main action sits where the eye ends up.
        int right = x + width - PADDING;
        int leftEdge = x + PADDING;
        for (int i = actions.size() - 1; i >= 0; i--) {
            Action action = actions.get(i);
            String label = label(action);
            if (leftAligned(action)) {
                buttons.add(new Hit(action, leftEdge, buttonY, widths[i], label));
                leftEdge += widths[i] + BUTTON_GAP;
                continue;
            }
            right -= widths[i];
            buttons.add(new Hit(action, right, buttonY, widths[i], label));
            right -= BUTTON_GAP;
        }
        return new Layout(x, y, width, height, body, y + modeY, modeAbout, policy,
                y + linkY, buttons);
    }

    private static List<String> wrap(Font font, String value, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.split("\n", -1)) {
            String rest = paragraph;
            if (rest.isEmpty()) {
                lines.add("");
                continue;
            }
            while (!rest.isEmpty()) {
                String head = font.plainSubstrByWidth(rest, width);
                if (head.isEmpty()) {
                    head = rest.substring(0, 1);
                }
                if (head.length() < rest.length()) {
                    int space = head.lastIndexOf(' ');
                    if (space > head.length() / 3) {
                        head = head.substring(0, space);
                    }
                }
                lines.add(head);
                rest = rest.substring(head.length()).stripLeading();
            }
        }
        return lines;
    }

    private static String clip(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String fit = font.plainSubstrByWidth(value, Math.max(0, maxWidth - font.width("…")), false);
        return fit.isEmpty() ? "…" : fit + "…";
    }

    // --- input ----------------------------------------------------------------------------------

    public void handleScreenMouseClick(double mouseX, double mouseY, int button) {
        if (!visible || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return;
        }
        Layout layout = layout(Minecraft.getInstance().font);
        if (mouseX < layout.x || mouseX >= layout.x + layout.width
                || mouseY < layout.y || mouseY >= layout.y + layout.height) {
            // Outside the popup: only a choice nobody has made yet is dropped by it. A link
            // already made stays up until it is closed, so it is not lost to a stray click.
            if (stage == Stage.CHOOSE) {
                close();
            }
            return;
        }
        for (Hit hit : layout.buttons) {
            if (mouseX >= hit.x() && mouseX < hit.x() + hit.width()
                    && mouseY >= hit.y() && mouseY < hit.y() + BUTTON_H) {
                run(hit.action());
                return;
            }
        }
    }

    public void handleScreenKeyPressed(int keyCode) {
        if (!visible) {
            return;
        }
        if (keyCode == InputConstants.KEY_ESCAPE) {
            close();
        } else if ((keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER)
                && primaryAction() != null) {
            run(primaryAction());
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible;
    }
}
