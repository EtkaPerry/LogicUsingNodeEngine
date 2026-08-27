package com.etka.lune.client.gui.tab;

/**
 * Where everything on the {@link MainTab} dashboard goes, for a given content area.
 * <p>
 * Kept free of Minecraft types and away from the tab itself for two reasons: the widget positions,
 * the panel backgrounds and the text drawn over them all read from one derivation instead of three
 * copies that can drift, and the part with actual decisions in it - which shape to use, how many
 * lines a card can hold - is unit-testable without a client.
 * <p>
 * There are three shapes, in order of how much room there is. Wide enough and the two cards share
 * a row as they always have. Narrower and they stack, because a card under {@value #MIN_CARD_W}px
 * turns every value into an ellipsis. Shorter than two stacked cards and the telemetry card goes
 * entirely: the question this tab exists to answer is what the bot is doing, and position and
 * health are already on the HUD.
 */
record DashboardFrame(int left, int top, int width, DashboardFrame.Card status,
                      DashboardFrame.Card telemetry, int queueTop, int queueHeight,
                      int buttonY, int hintY, boolean sharedButtonRow) {

    static final int MARGIN = 10;
    static final int GAP = 8;
    static final int CONTROL_H = 22;
    static final int TITLE_H = 18;
    static final int CARD_H = 116;
    /** Row reserved under the buttons for the keyboard hint, which used to hang off the screen. */
    static final int HINT_H = 12;
    /** One label/value line, and the smallest card that can still show one of them. */
    static final int ROW_H = 17;
    static final int BODY_TOP = 9;
    static final int MIN_CARD_H = TITLE_H + BODY_TOP + 9 + 4;
    /** Neither card reads well below this, so under twice it plus the gap they stop sharing a row. */
    static final int MIN_CARD_W = 180;
    static final int STACK_WIDTH = 420;
    /** The queue keeps at least its header and one entry before the cards are allowed any height. */
    static final int MIN_QUEUE_H = 46;
    /** Natural width of the three buttons plus their gaps; under it they share the row evenly. */
    static final int BUTTON_ROW_W = 258;
    static final int BUTTON_GAP = 6;
    /** Most lines any card has to offer. */
    static final int MAX_ROWS = 5;

    /**
     * One status card. {@code rows} is how many of its lines the height can actually hold, so the
     * card decides what to drop rather than drawing past its own border.
     */
    record Card(int x, int y, int width, int height, int rows) {

        static Card at(int x, int y, int width, int height) {
            return new Card(x, y, width, height,
                    Math.clamp((height - TITLE_H - BODY_TOP - 4) / ROW_H + 1, 1, MAX_ROWS));
        }
    }

    static DashboardFrame of(int areaLeft, int areaTop, int areaRight, int areaBottom) {
        int left = areaLeft + MARGIN;
        int top = areaTop + MARGIN;
        int width = Math.max(80, areaRight - MARGIN - left);
        int buttonY = areaBottom - MARGIN - HINT_H - CONTROL_H;
        int budget = buttonY - GAP - MIN_QUEUE_H - GAP - top;

        Card status;
        Card telemetry;
        if (width >= STACK_WIDTH) {
            int split = Math.clamp((width - GAP) * 3 / 5, MIN_CARD_W,
                    Math.max(MIN_CARD_W, width - GAP - MIN_CARD_W));
            int height = cardHeight(budget);
            status = Card.at(left, top, split, height);
            telemetry = Card.at(left + split + GAP, top, width - split - GAP, height);
        } else if (budget >= MIN_CARD_H * 2 + GAP) {
            int height = cardHeight((budget - GAP) / 2);
            status = Card.at(left, top, width, height);
            telemetry = Card.at(left, top + height + GAP, width, height);
        } else {
            status = Card.at(left, top, width, cardHeight(budget));
            telemetry = null;
        }

        int cardsBottom = telemetry == null
                ? status.y() + status.height()
                : Math.max(status.y() + status.height(), telemetry.y() + telemetry.height());
        int queueTop = cardsBottom + GAP;
        return new DashboardFrame(left, top, width, status, telemetry, queueTop,
                Math.max(40, buttonY - GAP - queueTop), buttonY, buttonY + CONTROL_H + 3,
                width < BUTTON_ROW_W);
    }

    private static int cardHeight(int available) {
        return Math.clamp(available, MIN_CARD_H, CARD_H);
    }
}
