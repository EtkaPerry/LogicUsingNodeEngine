package com.etka.lune.client.gui.mascot;

import com.etka.lune.util.Lang;
import com.etka.lune.bot.BotEngine;
import com.etka.lune.bot.Task;
import com.etka.lune.bot.TaskProgress;
import com.etka.lune.client.gui.LuneScreen;
import com.etka.lune.config.BotConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/** Screen-level Lune assistant with contextual speech bubbles. */
public final class MascotWidget extends AbstractWidget {

    private static final int NORMAL_ART_SIZE = 72;
    private static final long HOLD_TO_MOVE_MILLIS = 650L;
    private static final int BUBBLE_BORDER = 0xFF65758B;
    private static final int BUBBLE_BG = 0xF2222D3A;
    private static final int BUBBLE_HEADER = 0xF02A3544;
    private static final int BUTTON = 0xFF303846;
    private static final int BUTTON_HOVER = 0xFF425269;
    private static final int BUTTON_YES = 0xFF315C45;
    private static final int BUTTON_YES_HOVER = 0xFF3D7656;
    private static final int PROGRESS_BG = 0xFF151A22;
    private static final int PROGRESS_FILL = 0xFFF4C95D;
    private static final int BLOCKED_ACCENT = 0xFFE6A15C;
    private static final int WAITING_ACCENT = 0xFF8BB9D8;
    private static final int DANGER_ACCENT = 0xFFFF6868;
    private static final int SUCCESS_ACCENT = 0xFF7EDB92;
    private static final int INVENTORY_ACCENT = 0xFFE4A45F;
    private static final int MISSING_ACCENT = 0xFFC8A6F2;
    private static final int PAUSED_ACCENT = 0xFF91A4BE;

    private Runnable trainingHint;
    private Runnable trainingAnswer;
    private String trainingSpeech = "";
    private int trainingPage;
    private int trainingPages = 1;

    private final MascotAdvisor advisor = new MascotAdvisor();
    private final MascotRenderer mascot = new MascotRenderer();
    private boolean positionReady;
    private int positionedArtSize = NORMAL_ART_SIZE;
    private int artBaseX;
    private int artBaseY;
    private int renderedArtX;
    private int renderedArtY;
    private int bubbleX;
    private int bubbleY;
    private int bubbleWidth;
    private int bubbleHeight;
    private boolean bubbleVisible;
    private boolean closeVisible;
    private boolean bubbleDismissed;
    private String bubbleConversationKey = "";
    private int closeButtonX;
    private int closeButtonY;
    private int closeButtonSize;
    private boolean hasPromptButtons;
    private int yesX;
    private int yesY;
    private int yesWidth;
    private int noX;
    private int noWidth;
    private int arrowX;
    private int arrowWidth;
    private boolean amountControlsVisible;
    private int minusX;
    private int amountY;
    private int plusX;
    private int amountButtonWidth;
    private boolean undoVisible;
    private int undoX;
    private int undoY;
    private int undoWidth;
    private boolean dismissalOptionsVisible;
    private final int[] dismissalRowY = new int[MascotAdvisor.Dismissal.values().length];
    private int dismissalX;
    private int dismissalWidth;
    private int dismissalBackY;
    private long holdStartedAt;
    private int holdOffsetX;
    private int holdOffsetY;
    private boolean holdingMascot;
    private boolean movingMascot;

    public MascotWidget() {
        super(0, 0, 1, 1, Component.literal(Lang.get("lune.gui.mascot.lune_assistant")));
    }

    /** Gives Lune the whole area below the tab bar without making transparent space clickable. */
    public void setScreenArea(ScreenRectangle area) {
        setPosition(area.left(), area.top());
        setSize(Math.max(1, area.width()), Math.max(1, area.height()));
        restorePosition();
    }

    public void tick() {
        advisor.tick(BotEngine.get());
    }

    public void setSurface(MascotAdvisor.Surface surface) {
        advisor.setSurface(surface);
    }

    public void setTrainingLine(String line) {
        String value = line == null ? "" : line;
        if (!value.equals(trainingSpeech)) trainingPage = 0;
        trainingSpeech = value;
        advisor.setTrainingLine(line);
    }

    public void setTrainingHelp(Runnable hint, Runnable answer) {
        trainingHint = hint;
        trainingAnswer = answer;
    }

    /** Rendered explicitly by {@link LuneScreen} after the active tab's foreground. */
    public void renderOverlay(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                              float partialTick) {
        if (visible) {
            extractWidgetRenderState(extractor, mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
                                            float partialTick) {
        BotEngine engine = BotEngine.get();
        BotConfig config = BotConfig.get();
        if (!config.showLune && trainingHint == null) {
            bubbleVisible = false;
            closeVisible = false;
            bubbleDismissed = false;
            bubbleConversationKey = "";
            holdingMascot = false;
            movingMascot = false;
            return;
        }
        Font font = Minecraft.getInstance().font;
        long now = Util.getMillis();
        int artSize = artSize(config);
        if (!positionReady || positionedArtSize != artSize) {
            restorePosition();
        }

        MascotAdvisor.Mood mood = advisor.mood(engine);
        int driftX = switch (mood) {
            case QUIET, RESTING, PAUSED, DEAD -> 0;
            case DANGER -> (int) Math.round(Math.sin(now / 75.0) * 3.0);
            case BLOCKED -> (int) Math.round(Math.sin(now / 95.0) * 2.0);
            case WAITING -> (int) Math.round(Math.sin(now / 1600.0));
            default -> (int) Math.round(Math.sin(now / 1150.0) * 2.0);
        };
        int hoverY = switch (mood) {
            case QUIET, RESTING -> 2 + (int) Math.round(Math.sin(now / 1400.0) * 2.0);
            case DEAD -> 3;
            case PAUSED -> 2;
            case DANGER -> (int) Math.round(Math.sin(now / 180.0) * 2.0);
            case BLOCKED -> 1 + (int) Math.round(Math.sin(now / 1100.0));
            case WAITING -> 2 + (int) Math.round(Math.sin(now / 1500.0));
            case THINKING, MISSING_MATERIALS ->
                    (int) Math.round(Math.sin(now / 900.0) * 2.0);
            case SUCCESS -> (int) Math.round(Math.sin(now / 520.0) * 4.0);
            default -> (int) Math.round(Math.sin(now / 700.0) * 3.0);
        };
        renderedArtX = artBaseX + driftX;
        renderedArtY = artBaseY + hoverY;
        mascot.draw(extractor, mood, renderedArtX, renderedArtY, artSize, now);

        if (trainingHint != null) {
            drawTrainingHelp(extractor, font, mouseX, mouseY, artSize);
            return;
        }

        hasPromptButtons = false;
        amountControlsVisible = false;
        undoVisible = false;
        dismissalOptionsVisible = false;
        closeVisible = false;
        boolean shouldSpeak = advisor.shouldSpeak(engine);
        String conversationKey = advisor.conversationKey(engine);
        if (!conversationKey.equals(bubbleConversationKey)) {
            bubbleConversationKey = conversationKey;
            bubbleDismissed = false;
        }
        bubbleVisible = shouldSpeak && !bubbleDismissed;
        if (!bubbleVisible) {
            return;
        }

        Task current = engine.getCurrent();
        TaskProgress progress = current == null ? null : current.progress();
        boolean dismissalMenu = advisor.isDismissalMenu();
        boolean preview = !dismissalMenu && advisor.hasPreview();
        boolean canUndo = !dismissalMenu && advisor.canUndo();
        boolean expandedState = expandedState(mood);
        layoutBubble(advisor.isPrompting(), advisor.hasAmountChoice(), progress != null,
                expandedState, preview, canUndo, dismissalMenu, artSize, config.luneChatboxSize);
        boolean bubbleOnLeft = bubbleX < renderedArtX;
        drawBubble(extractor, bubbleX, bubbleY, bubbleWidth, bubbleHeight,
                renderedArtX, renderedArtY, artSize, bubbleOnLeft);
        drawCloseButton(extractor, font, mouseX, mouseY);

        String title = advisor.isPrompting() ? advisor.promptTitle() : stateTitle(mood);
        extractor.textRenderer().accept(bubbleX + 8, bubbleY + 6,
                Component.literal(fit(font, title, bubbleWidth - 34))
                        .withColor(stateColour(mood)));

        int textX = bubbleX + 8;
        int textWidth = Math.max(40, bubbleWidth - 16);
        int bodyY = bubbleY + 23;
        if (!advisor.isPrompting() && current != null) {
            extractor.textRenderer().accept(textX, bodyY,
                    Component.literal(fit(font, current.name(), textWidth)).withColor(LuneScreen.TEXT));
            bodyY += 14;
        }

        int lineCount = advisor.isPrompting() ? dismissalMenu ? 1 : preview ? 2 : 3
                : expandedState ? 3 : current == null ? 3 : progress == null ? 2 : 1;
        for (String line : wrap(font, advisor.speech(engine), textWidth, lineCount)) {
            extractor.textRenderer().accept(textX, bodyY,
                    Component.literal(line).withColor(advisor.isPrompting()
                            ? LuneScreen.TEXT : LuneScreen.TEXT_DIM));
            bodyY += 11;
        }

        if (advisor.isPrompting()) {
            if (dismissalMenu) {
                drawDismissalOptions(extractor, font, mouseX, mouseY, bubbleX + 8,
                        bubbleWidth - 16, bubbleY + 24);
            } else if (preview) {
                int previewY = bubbleY + bubbleHeight
                        - (advisor.hasAmountChoice() ? 67 : 46);
                drawPreview(extractor, font, textX, textWidth, previewY,
                        advisor.previewBefore(), advisor.previewAfter());
            }
            if (!dismissalMenu) {
                if (advisor.hasAmountChoice()) {
                    drawAmountPicker(extractor, font, mouseX, mouseY, bubbleX + 8,
                            bubbleWidth - 16, bubbleY + bubbleHeight - 41);
                }
                drawPromptButtons(extractor, font, mouseX, mouseY, bubbleX + 8,
                        bubbleWidth - 16, bubbleY + bubbleHeight - 20);
            }
        } else if (canUndo) {
            drawUndoButton(extractor, font, mouseX, mouseY, bubbleX + 8,
                    bubbleWidth - 16, bubbleY + bubbleHeight - 20);
        } else if (progress != null) {
            drawProgress(extractor, font, bubbleX + 8, bubbleWidth - 16,
                    bubbleY + bubbleHeight - 25, progress);
        }
    }

    /** Training help stays beside Lune, including explicitly requested hints and answers. */
    private void drawTrainingHelp(GuiGraphicsExtractor extractor, Font font, int mouseX, int mouseY,
                                  int artSize) {
        hasPromptButtons = false;
        amountControlsVisible = false;
        undoVisible = false;
        dismissalOptionsVisible = false;
        closeVisible = false;
        bubbleVisible = true;
        bubbleWidth = Math.min(320, getWidth() - 16);
        var lines = wrap(font, trainingSpeech, bubbleWidth - 16, Integer.MAX_VALUE);
        bubbleHeight = Math.min(getHeight() - 16, Math.max(100, 53 + lines.size() * 11));
        bubbleX = Math.clamp(renderedArtX - bubbleWidth - 9, getX() + 4,
                Math.max(getX() + 4, getX() + getWidth() - bubbleWidth - 4));
        bubbleY = Math.clamp(renderedArtY + artSize / 2 - bubbleHeight / 2, getY() + 4,
                Math.max(getY() + 4, getY() + getHeight() - bubbleHeight - 4));
        drawBubble(extractor, bubbleX, bubbleY, bubbleWidth, bubbleHeight,
                renderedArtX, renderedArtY, artSize, bubbleX < renderedArtX);
        extractor.textRenderer().accept(bubbleX + 8, bubbleY + 6,
                Component.literal(Lang.get("lune.gui.mascot.lune_training")).withColor(LuneScreen.ACCENT));
        int capacity = Math.max(1, (bubbleHeight - 53) / 11);
        trainingPages = Math.max(1, (lines.size() + capacity - 1) / capacity);
        trainingPage = Math.min(trainingPage, trainingPages - 1);
        for (int i = 0; i < capacity && trainingPage * capacity + i < lines.size(); i++) {
            extractor.textRenderer().accept(bubbleX + 8, bubbleY + 25 + i * 11,
                    Component.literal(lines.get(trainingPage * capacity + i)).withColor(LuneScreen.TEXT));
        }
        yesX = bubbleX + 8;
        yesY = bubbleY + bubbleHeight - 21;
        yesWidth = (bubbleWidth - 24) / (trainingPages > 1 ? 3 : 2);
        noX = yesX + yesWidth + 4;
        noWidth = yesWidth;
        button(extractor, font, yesX, yesY, yesWidth,
                inside(mouseX, mouseY, yesX, yesY, yesWidth, 15) ? BUTTON_HOVER : BUTTON, Lang.get("lune.gui.mascot.hint"));
        button(extractor, font, noX, yesY, noWidth,
                inside(mouseX, mouseY, noX, yesY, noWidth, 15) ? BUTTON_HOVER : BUTTON, Lang.get("lune.gui.mascot.answer"));
        if (trainingPages > 1) {
            arrowX = noX + noWidth + 4;
            arrowWidth = yesWidth;
            button(extractor, font, arrowX, yesY, arrowWidth, BUTTON,
                    Lang.get("lune.mascot.more_pages", trainingPage + 1, trainingPages));
        }
    }

    private void layoutBubble(boolean prompting, boolean amountChoice, boolean progressVisible,
                              boolean expandedState, boolean preview, boolean undo,
                              boolean dismissalMenu,
                              int artSize, String sizeSetting) {
        boolean small = BotConfig.LUNE_SIZE_SMALL.equalsIgnoreCase(sizeSetting);
        boolean large = BotConfig.LUNE_SIZE_LARGE.equalsIgnoreCase(sizeSetting);
        int wantedHeight = dismissalMenu
                ? (small ? 220 : large ? 250 : 235)
                : prompting
                ? amountChoice ? (small ? 112 : large ? 132 : 122)
                : (small ? 88 : large ? 106 : 96)
                : expandedState ? progressVisible
                ? (small ? 108 : large ? 138 : 124)
                : (small ? 90 : large ? 116 : 104)
                : progressVisible ? (small ? 84 : large ? 102 : 92)
                : (small ? 70 : large ? 88 : 78);
        if (preview) {
            wantedHeight += 28;
        } else if (undo) {
            wantedHeight += 20;
        }
        int widthLimit = small ? 230 : large ? 380 : 300;
        bubbleHeight = Math.min(wantedHeight, getHeight() - 8);
        int roomLeft = renderedArtX - getX() - 10;
        int roomRight = getX() + getWidth() - (renderedArtX + artSize) - 10;
        boolean placeLeft = roomLeft >= roomRight;
        int available = Math.max(92, placeLeft ? roomLeft : roomRight);
        bubbleWidth = Math.min(widthLimit, available);
        bubbleX = placeLeft
                ? renderedArtX - bubbleWidth - 9
                : renderedArtX + artSize + 9;
        bubbleX = Math.clamp(bubbleX, getX() + 4,
                Math.max(getX() + 4, getX() + getWidth() - bubbleWidth - 4));
        bubbleY = Math.clamp(renderedArtY + artSize / 2 - bubbleHeight / 2,
                getY() + 4, Math.max(getY() + 4, getY() + getHeight() - bubbleHeight - 4));
    }

    private static void drawBubble(GuiGraphicsExtractor extractor, int x, int y, int width,
                                   int height, int artX, int artY, int artSize,
                                   boolean bubbleOnLeft) {
        extractor.fill(x + 2, y + 3, x + width + 2, y + height + 3, 0x50000000);
        extractor.fill(x, y, x + width, y + height, BUBBLE_BORDER);
        extractor.fill(x + 1, y + 1, x + width - 1, y + height - 1, BUBBLE_BG);
        extractor.fill(x + 1, y + 1, x + width - 1, y + 19, BUBBLE_HEADER);

        int pointerY = Math.clamp(artY + artSize / 2, y + 22, y + height - 12);
        if (bubbleOnLeft) {
            extractor.fill(x + width, pointerY - 5, x + width + 4, pointerY + 5, BUBBLE_BORDER);
            extractor.fill(x + width + 4, pointerY - 3, x + width + 7, pointerY + 3, BUBBLE_BORDER);
            extractor.fill(x + width, pointerY - 3, x + width + 4, pointerY + 3, BUBBLE_BG);
        } else {
            extractor.fill(x - 4, pointerY - 5, x, pointerY + 5, BUBBLE_BORDER);
            extractor.fill(x - 7, pointerY - 3, x - 4, pointerY + 3, BUBBLE_BORDER);
            extractor.fill(x - 4, pointerY - 3, x, pointerY + 3, BUBBLE_BG);
        }
    }

    private void drawCloseButton(GuiGraphicsExtractor extractor, Font font, int mouseX, int mouseY) {
        closeButtonSize = 14;
        closeButtonX = bubbleX + bubbleWidth - closeButtonSize - 4;
        closeButtonY = bubbleY + 3;
        closeVisible = true;
        boolean hover = inside(mouseX, mouseY, closeButtonX, closeButtonY,
                closeButtonSize, closeButtonSize);
        extractor.fill(closeButtonX, closeButtonY,
                closeButtonX + closeButtonSize, closeButtonY + closeButtonSize,
                hover ? BUTTON_HOVER : BUTTON);
        String mark = "X";
        extractor.textRenderer().accept(
                closeButtonX + Math.max(2, (closeButtonSize - font.width(mark)) / 2),
                closeButtonY + 3,
                Component.literal(mark).withColor(LuneScreen.TEXT));
    }

    private static boolean expandedState(MascotAdvisor.Mood mood) {
        return switch (mood) {
            case WAITING, BLOCKED, DANGER, SUCCESS, INVENTORY_FULL, MISSING_MATERIALS, PAUSED,
                    FIGHT, FLEE, HURT, RECOVERING, DEAD, EFFECT, HUNGRY, STALLED -> true;
            default -> false;
        };
    }

    private static String stateTitle(MascotAdvisor.Mood mood) {
        return switch (mood) {
            case WORKING -> Lang.get("lune.mascot.banner.working");
            case WAITING -> Lang.get("lune.mascot.banner.waiting");
            case BLOCKED -> Lang.get("lune.mascot.banner.blocked");
            case DANGER -> Lang.get("lune.mascot.banner.danger");
            case SUCCESS -> Lang.get("lune.mascot.banner.success");
            case INVENTORY_FULL -> Lang.get("lune.mascot.banner.inventory_full");
            case MISSING_MATERIALS -> Lang.get("lune.mascot.banner.missing_materials");
            case PAUSED -> Lang.get("lune.mascot.banner.paused");
            case THINKING -> Lang.get("lune.mascot.banner.thinking");
            case QUIET -> Lang.get("lune.mascot.banner.sleeping");
            case RESTING -> Lang.get("lune.mascot.banner.resting");
            case FIGHT -> Lang.get("lune.mascot.banner.fight");
            case FLEE -> Lang.get("lune.mascot.banner.flee");
            case HURT -> Lang.get("lune.mascot.banner.hurt");
            case RECOVERING -> Lang.get("lune.mascot.banner.recovering");
            case DEAD -> Lang.get("lune.mascot.banner.dead");
            case EFFECT -> Lang.get("lune.mascot.banner.effect");
            case HUNGRY -> Lang.get("lune.mascot.banner.hungry");
            case STALLED -> Lang.get("lune.mascot.banner.stalled");
            default -> Lang.get("lune.gui.about.lune");
        };
    }

    private static int stateColour(MascotAdvisor.Mood mood) {
        return switch (mood) {
            case WAITING -> WAITING_ACCENT;
            case BLOCKED -> BLOCKED_ACCENT;
            case DANGER, FIGHT, FLEE, HURT, DEAD -> DANGER_ACCENT;
            case RECOVERING, EFFECT, HUNGRY, STALLED -> MISSING_ACCENT;
            case SUCCESS -> SUCCESS_ACCENT;
            case INVENTORY_FULL -> INVENTORY_ACCENT;
            case MISSING_MATERIALS -> MISSING_ACCENT;
            case PAUSED -> PAUSED_ACCENT;
            default -> LuneScreen.ACCENT;
        };
    }

    private void drawPromptButtons(GuiGraphicsExtractor extractor, Font font, int mouseX, int mouseY,
                                   int x, int width, int y) {
        int gap = 4;
        int snoozeWidth = Math.min(92, Math.max(72, width / 3));
        yesWidth = Math.max(34, width - snoozeWidth - gap);
        yesX = x;
        yesY = y;
        noX = x + yesWidth + gap;
        noWidth = Math.max(48, width - yesWidth - gap);
        arrowWidth = Math.min(20, noWidth - 1);
        arrowX = noX + noWidth - arrowWidth;
        hasPromptButtons = true;

        boolean yesHover = inside(mouseX, mouseY, yesX, yesY, yesWidth, 15);
        boolean noHover = inside(mouseX, mouseY, noX, yesY, noWidth, 15);
        boolean arrowHover = inside(mouseX, mouseY, arrowX, yesY, arrowWidth, 15);
        button(extractor, font, yesX, yesY, yesWidth,
                yesHover ? BUTTON_YES_HOVER : BUTTON_YES, advisor.acceptLabel());
        button(extractor, font, noX, yesY, noWidth,
                noHover ? BUTTON_HOVER : BUTTON, Lang.get("lune.gui.mascot.now"));
        extractor.fill(arrowX, yesY, arrowX + arrowWidth, yesY + 15,
                arrowHover ? BUTTON_HOVER : BUTTON);
        extractor.textRenderer().accept(arrowX + Math.max(2, (arrowWidth - font.width("▼")) / 2),
                yesY + 4, Component.literal("▼").withColor(LuneScreen.TEXT));
    }

    private void drawDismissalOptions(GuiGraphicsExtractor extractor, Font font,
                                      int mouseX, int mouseY, int x, int width, int y) {
        dismissalOptionsVisible = true;
        dismissalX = x;
        dismissalWidth = width;
        extractor.textRenderer().accept(x, y,
                Component.literal(Lang.get("lune.gui.mascot.how_should_i_remember")).withColor(LuneScreen.TEXT_DIM));
        int rowY = y + 14;
        int rowHeight = 31;
        for (int i = 0; i < MascotAdvisor.Dismissal.values().length; i++) {
            dismissalRowY[i] = rowY;
            MascotAdvisor.Dismissal choice = MascotAdvisor.Dismissal.values()[i];
            boolean hover = inside(mouseX, mouseY, x, rowY, width, rowHeight);
            extractor.fill(x, rowY, x + width, rowY + rowHeight,
                    hover ? BUTTON_HOVER : BUTTON);
            extractor.textRenderer().accept(x + 6, rowY + 4,
                    Component.literal(fit(font, choice.label(), width - 12))
                            .withColor(choice == MascotAdvisor.Dismissal.NEVER_TYPE
                                    ? 0xFFFF7777 : LuneScreen.TEXT));
            extractor.textRenderer().accept(x + 6, rowY + 16,
                    Component.literal(fit(font, choice.description(), width - 12))
                            .withColor(choice == MascotAdvisor.Dismissal.NEVER_TYPE
                                    ? 0xFFCC7777 : LuneScreen.TEXT_DIM));
            rowY += rowHeight + 1;
        }
        dismissalBackY = rowY;
        boolean backHover = inside(mouseX, mouseY, x, dismissalBackY, width, 18);
        button(extractor, font, x, dismissalBackY, width,
                backHover ? BUTTON_HOVER : PROGRESS_BG, Lang.get("lune.gui.mascot.back"));
    }

    private static void drawPreview(GuiGraphicsExtractor extractor, Font font, int x, int width,
                                    int y, String before, String after) {
        extractor.fill(x, y, x + width, y + 25, PROGRESS_BG);
        extractor.textRenderer().accept(x + 4, y + 3,
                Component.literal(fit(font, Lang.get("lune.mascot.preview_before", before), width - 8))
                        .withColor(LuneScreen.TEXT_DIM));
        extractor.textRenderer().accept(x + 4, y + 14,
                Component.literal(fit(font, Lang.get("lune.mascot.preview_after", after), width - 8))
                        .withColor(PROGRESS_FILL));
    }

    private void drawUndoButton(GuiGraphicsExtractor extractor, Font font, int mouseX, int mouseY,
                                int x, int width, int y) {
        undoX = x;
        undoY = y;
        undoWidth = width;
        undoVisible = true;
        boolean hover = inside(mouseX, mouseY, x, y, width, 15);
        button(extractor, font, x, y, width, hover ? BUTTON_HOVER : BUTTON, Lang.get("lune.gui.tasks.undo"));
    }

    private void drawAmountPicker(GuiGraphicsExtractor extractor, Font font, int mouseX, int mouseY,
                                  int x, int width, int y) {
        int gap = 4;
        amountButtonWidth = 22;
        minusX = x;
        plusX = x + width - amountButtonWidth;
        amountY = y;
        int valueX = minusX + amountButtonWidth + gap;
        int valueWidth = Math.max(28, plusX - gap - valueX);
        amountControlsVisible = true;

        boolean minusHover = inside(mouseX, mouseY, minusX, amountY, amountButtonWidth, 15);
        boolean plusHover = inside(mouseX, mouseY, plusX, amountY, amountButtonWidth, 15);
        button(extractor, font, minusX, amountY, amountButtonWidth,
                minusHover ? BUTTON_HOVER : BUTTON, "−");
        extractor.fill(valueX, amountY, valueX + valueWidth, amountY + 15, BUBBLE_BORDER);
        extractor.fill(valueX + 1, amountY + 1, valueX + valueWidth - 1, amountY + 14, PROGRESS_BG);
        String amount = fit(font, advisor.chosenAmountLabel(), valueWidth - 6);
        extractor.textRenderer().accept(valueX + Math.max(3, (valueWidth - font.width(amount)) / 2),
                amountY + 4, Component.literal(amount).withColor(PROGRESS_FILL));
        button(extractor, font, plusX, amountY, amountButtonWidth,
                plusHover ? BUTTON_HOVER : BUTTON, "+");
    }

    private static void drawProgress(GuiGraphicsExtractor extractor, Font font, int x, int width,
                                     int y, TaskProgress progress) {
        String label = fit(font, progress.label(), width);
        extractor.textRenderer().accept(x, y, Component.literal(label).withColor(PROGRESS_FILL));
        int barY = y + 12;
        extractor.fill(x, barY, x + width, barY + 5, PROGRESS_BG);
        int filled = Math.round(width * progress.fraction());
        if (filled > 0) {
            extractor.fill(x, barY, x + filled, barY + 5, PROGRESS_FILL);
        }
    }

    private static void button(GuiGraphicsExtractor extractor, Font font, int x, int y,
                               int width, int colour, String label) {
        extractor.fill(x, y, x + width, y + 15, colour);
        String visible = fit(font, label, Math.max(1, width - 4));
        int textX = x + Math.max(2, (width - font.width(visible)) / 2);
        extractor.textRenderer().accept(textX, y + 4,
                Component.literal(visible).withColor(LuneScreen.TEXT));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        if (!visible || (!BotConfig.get().showLune && trainingHint == null)) {
            return false;
        }
        int artSize = artSize(BotConfig.get());
        if (inside(mouseX, mouseY, renderedArtX, renderedArtY, artSize, artSize)) {
            return true;
        }
        return bubbleVisible && inside(mouseX, mouseY, bubbleX, bubbleY, bubbleWidth, bubbleHeight);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (trainingHint != null && bubbleVisible) {
            if (inside(event.x(), event.y(), yesX, yesY, yesWidth, 15)) {
                trainingHint.run();
                return;
            }
            if (inside(event.x(), event.y(), noX, yesY, noWidth, 15)) {
                trainingAnswer.run();
                return;
            }
            if (trainingPages > 1 && inside(event.x(), event.y(), arrowX, yesY, arrowWidth, 15)) {
                trainingPage = (trainingPage + 1) % trainingPages;
                return;
            }
        }
        if (bubbleVisible && closeVisible
                && inside(event.x(), event.y(), closeButtonX, closeButtonY,
                closeButtonSize, closeButtonSize)) {
            bubbleDismissed = true;
            bubbleVisible = false;
            closeVisible = false;
            return;
        }
        if (dismissalOptionsVisible && advisor.isDismissalMenu()) {
            for (int i = 0; i < dismissalRowY.length; i++) {
                if (inside(event.x(), event.y(), dismissalX, dismissalRowY[i], dismissalWidth, 31)) {
                    advisor.chooseDismissal(i);
                    return;
                }
            }
            if (inside(event.x(), event.y(), dismissalX, dismissalBackY, dismissalWidth, 18)) {
                advisor.hideDismissalMenu();
            }
            return;
        }
        if (undoVisible && inside(event.x(), event.y(), undoX, undoY, undoWidth, 15)) {
            advisor.undo();
            return;
        }
        if (advisor.isPrompting() && amountControlsVisible) {
            if (inside(event.x(), event.y(), minusX, amountY, amountButtonWidth, 15)) {
                advisor.decreaseAmount();
                return;
            }
            if (inside(event.x(), event.y(), plusX, amountY, amountButtonWidth, 15)) {
                advisor.increaseAmount();
                return;
            }
        }
        if (advisor.isPrompting() && hasPromptButtons) {
            if (inside(event.x(), event.y(), yesX, yesY, yesWidth, 15)) {
                advisor.accept();
                return;
            }
            if (inside(event.x(), event.y(), arrowX, yesY, arrowWidth, 15)) {
                advisor.showDismissalMenu();
                return;
            }
            if (inside(event.x(), event.y(), noX, yesY, noWidth, 15)) {
                advisor.chooseDismissal(0);
                return;
            }
        }
        int artSize = artSize(BotConfig.get());
        if (inside(event.x(), event.y(), renderedArtX, renderedArtY, artSize, artSize)) {
            holdingMascot = true;
            movingMascot = false;
            holdStartedAt = Util.getMillis();
            holdOffsetX = (int) event.x() - artBaseX;
            holdOffsetY = (int) event.y() - artBaseY;
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        if (!holdingMascot) {
            return;
        }
        if (!movingMascot) {
            if (Util.getMillis() - holdStartedAt < HOLD_TO_MOVE_MILLIS) {
                return;
            }
            movingMascot = true;
        }

        int artSize = artSize(BotConfig.get());
        int rangeX = Math.max(0, getWidth() - artSize);
        int rangeY = Math.max(0, getHeight() - artSize);
        artBaseX = Math.clamp((int) event.x() - holdOffsetX, getX(), getX() + rangeX);
        artBaseY = Math.clamp((int) event.y() - holdOffsetY, getY(), getY() + rangeY);
        renderedArtX = artBaseX;
        renderedArtY = artBaseY;
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        if (movingMascot) {
            savePosition();
        }
        holdingMascot = false;
        movingMascot = false;
    }

    private void restorePosition() {
        BotConfig config = BotConfig.get();
        int artSize = artSize(config);
        int rangeX = Math.max(0, getWidth() - artSize);
        int rangeY = Math.max(0, getHeight() - artSize);
        float screenX = Float.isFinite(config.mascotScreenX)
                ? Math.clamp(config.mascotScreenX, 0.0F, 1.0F) : 0.78F;
        float screenY = Float.isFinite(config.mascotScreenY)
                ? Math.clamp(config.mascotScreenY, 0.0F, 1.0F) : 0.20F;
        artBaseX = getX() + Math.round(screenX * rangeX);
        artBaseY = getY() + Math.round(screenY * rangeY);
        renderedArtX = artBaseX;
        renderedArtY = artBaseY;
        positionedArtSize = artSize;
        positionReady = true;
    }

    private void savePosition() {
        BotConfig config = BotConfig.get();
        int artSize = artSize(config);
        int rangeX = Math.max(1, getWidth() - artSize);
        int rangeY = Math.max(1, getHeight() - artSize);
        config.mascotScreenX = Math.clamp((float) (artBaseX - getX()) / rangeX, 0.0F, 1.0F);
        config.mascotScreenY = Math.clamp((float) (artBaseY - getY()) / rangeY, 0.0F, 1.0F);
        config.save();
    }

    private static int artSize(BotConfig config) {
        if (BotConfig.LUNE_SIZE_SMALL.equalsIgnoreCase(config.luneSize)) {
            return 54;
        }
        if (BotConfig.LUNE_SIZE_LARGE.equalsIgnoreCase(config.luneSize)) {
            return 96;
        }
        return NORMAL_ART_SIZE;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static List<String> wrap(Font font, String value, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        String[] words = value == null ? new String[0] : value.trim().split("\\s+");
        int index = 0;
        while (index < words.length && lines.size() < maxLines) {
            StringBuilder line = new StringBuilder();
            while (index < words.length) {
                String candidate = line.isEmpty() ? words[index] : line + " " + words[index];
                if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                    break;
                }
                line.setLength(0);
                line.append(candidate);
                index++;
                if (font.width(candidate) > maxWidth) {
                    break;
                }
            }
            if (!line.isEmpty()) {
                lines.add(fit(font, line.toString(), maxWidth));
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        if (index < words.length) {
            int last = lines.size() - 1;
            lines.set(last, fitWithEllipsis(font, lines.get(last), maxWidth));
        }
        return lines;
    }

    private static String fitWithEllipsis(Font font, String value, int maxWidth) {
        String suffix = "…";
        return font.plainSubstrByWidth(value,
                Math.max(0, maxWidth - font.width(suffix)), false) + suffix;
    }

    private static String fit(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }
        return fitWithEllipsis(font, value, maxWidth);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        BotEngine engine = BotEngine.get();
        String narration = advisor.shouldSpeak(engine)
                ? "Lune. " + advisor.speech(engine)
                : Lang.get("lune.gui.mascot.lune_quietly_hovering");
        if (advisor.isPrompting()) {
            narration += advisor.hasAmountChoice()
                    ? Lang.get("lune.gui.mascot.choose_amount_then_or_open_options", advisor.acceptLabel())
                    : Lang.get("lune.gui.mascot.or_open_options", advisor.acceptLabel());
            if (advisor.isDismissalMenu()) {
                narration += Lang.get("lune.gui.mascot.choose_how_lune_should_remember");
            }
        }
        if (advisor.canUndo()) {
            narration += Lang.get("lune.gui.mascot.undo_available");
        }
        output.add(NarratedElementType.TITLE, Component.literal(narration));
    }
}
