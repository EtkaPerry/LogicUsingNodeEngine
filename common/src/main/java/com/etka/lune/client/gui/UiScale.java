package com.etka.lune.client.gui;

import com.etka.lune.config.BotConfig;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * Picks the GUI scale Lune's own menus are drawn at, independently of the game's.
 * <p>
 * Lune's panels are laid out in fixed pixels sized for a roomy screen - three panes in the task
 * editor, two status cards plus a queue on Main. The player's GUI scale decides how many of those
 * pixels exist, and the default "Auto" scale is generous: a 1080p screen resolves to 4, leaving
 * 480x270 pixels, well under what the layout needs. Panels then collide, labels get ellipsised and
 * the bottom row falls off the screen.
 * <p>
 * Forcing a small scale is the obvious fix and the wrong one: the GUI scale is an accessibility
 * choice. A player on a TV, or one who simply cannot read 9px text, picked a big scale on purpose,
 * and a mod that quietly undoes it is worse than a cramped menu. So the rule here is to shrink by
 * the <em>smallest</em> whole step that buys enough room and then stop - never past half the
 * player's chosen scale, so a deliberately huge interface stays recognisably huge, and never in the
 * other direction, so Lune is never bigger than the player asked for. "Match game" turns it off
 * entirely.
 * <p>
 * Only whole scales are used. Rendering at, say, 3 inside a game running at 4 means every Lune
 * pixel is exactly 3 real pixels, so the 1px panel borders stay crisp; a fractional scale would
 * smear them.
 */
public final class UiScale {

    /** Smallest area the tab layouts are designed for, in Lune pixels. */
    private static final int MIN_WIDTH = 620;
    private static final int MIN_HEIGHT = 330;
    /** Compact trades size for room, so it keeps shrinking until it has plenty of both. */
    private static final int COMPACT_WIDTH = 800;
    private static final int COMPACT_HEIGHT = 430;
    /**
     * Smallest area the terms page is written for. Far less than a tab needs: one column of short
     * text and a row of buttons, which is the whole reason that page can stay legible on a screen
     * where the dashboard cannot.
     */
    private static final int TERMS_WIDTH = 300;
    private static final int TERMS_HEIGHT = 200;

    private UiScale() {
    }

    /** The GUI scale the game itself is drawing at, with "Auto" already resolved to a number. */
    public static int gameScale(Window window) {
        return Math.max(1, window.getGuiScale());
    }

    /** The GUI scale Lune's menus should draw at. Never larger than {@link #gameScale}. */
    public static int menuScale(Window window) {
        return menuScale(gameScale(window), window.getWidth(), window.getHeight(),
                BotConfig.get().luneUiScale);
    }

    /** The rule itself, free of the window so it can be reasoned about and tested directly. */
    static int menuScale(int gameScale, int pixelWidth, int pixelHeight, String preference) {
        return menuScale(gameScale, pixelWidth, pixelHeight, preference, 0);
    }

    /**
     * As {@link #menuScale(int, int, int, String)}, with the player's text-size step applied.
     *
     * <p>The step is the last word, and deliberately so. Everything above it is the layout arguing
     * about how much room it would like; the step is somebody saying they cannot read this. A
     * positive step can therefore push the layout back below what it asked for - which is what the
     * surfaces that honour it are expected to cope with, by reflowing or by scrolling.</p>
     */
    static int menuScale(int gameScale, int pixelWidth, int pixelHeight, String preference,
                         int textSizeStep) {
        int game = Math.max(1, gameScale);
        int scale = BotConfig.LUNE_UI_MATCH_GAME.equalsIgnoreCase(preference)
                ? game
                : fit(game, pixelWidth, pixelHeight,
                        BotConfig.LUNE_UI_COMPACT.equalsIgnoreCase(preference)
                                ? COMPACT_WIDTH : MIN_WIDTH,
                        BotConfig.LUNE_UI_COMPACT.equalsIgnoreCase(preference)
                                ? COMPACT_HEIGHT : MIN_HEIGHT);
        return Math.clamp(scale + textSizeStep, 1, game);
    }

    /** The scale Lune's menus draw at, including the player's text-size preference. */
    public static int menuScaleWithTextSize(Window window) {
        BotConfig config = BotConfig.get();
        return menuScale(gameScale(window), window.getWidth(), window.getHeight(),
                config.luneUiScale, BotConfig.textSizeStep(config.luneTextSize));
    }

    /**
     * The scale the terms page draws at.
     *
     * <p>It asks for a much smaller box than a tab, because it is a much smaller page - and because
     * it is the one page that has to work before the player can reach a single setting. The page
     * scrolls when even this does not fit, so a large step here costs scrolling rather than the
     * Accept button.</p>
     */
    public static int termsScale(Window window) {
        BotConfig config = BotConfig.get();
        int game = gameScale(window);
        int fitted = BotConfig.LUNE_UI_MATCH_GAME.equalsIgnoreCase(config.luneUiScale)
                ? game
                : fit(game, window.getWidth(), window.getHeight(), TERMS_WIDTH, TERMS_HEIGHT);
        return Math.clamp(fitted + BotConfig.textSizeStep(config.luneTextSize), 1, game);
    }

    /**
     * The largest whole scale no greater than {@code gameScale} that leaves the wanted box, never
     * going below half the player's own scale.
     *
     * <p>That floor is the accessibility half of the promise: someone on a TV, or someone who
     * simply cannot read 9px text, chose a large scale deliberately, and a mod that quietly undoes
     * it has substituted its own layout for their eyesight. Where the floor and the box disagree,
     * the floor wins and the surface is expected to cope.</p>
     */
    private static int fit(int gameScale, int pixelWidth, int pixelHeight,
                           int wantedWidth, int wantedHeight) {
        int floor = Math.max(1, gameScale / 2);
        int scale = gameScale;
        while (scale > floor
                && (pixelWidth / scale < wantedWidth || pixelHeight / scale < wantedHeight)) {
            scale--;
        }
        return scale;
    }

    /**
     * Converts a Lune pixel into a game GUI pixel. Needed for the few things vanilla draws for us
     * after the menu's transform has been popped - tooltips are deferred to the end of the frame,
     * so their anchor has to be handed over in the game's own coordinates.
     */
    public static int toGamePixels(int luneCoordinate) {
        Window window = Minecraft.getInstance().getWindow();
        return Math.round(luneCoordinate * (float) menuScaleWithTextSize(window) / gameScale(window));
    }
}
