package com.etka.lune.client.gui.tab;

/**
 * Where everything on the {@link MainTab} dashboard goes, for a given content area.
 * <p>
 * Kept free of Minecraft types and away from the tab itself for two reasons: the widget positions,
 * the panel backgrounds and the text drawn over them all read from one derivation instead of three
 * copies that can drift, and the part with actual decisions in it - which shape to use, how many
 * lines a card can hold - is unit-testable without a client.
 * <p>
 * The dashboard keeps the top answer in three vertically separated cards - status, recent
 * activity, and the live working node - and gives the lower area four independently sized columns:
 * saved tasks, player safety, world safety, and statistics. At smaller widths the status card moves
 * above the two right-hand cards so the lower panels remain available.
 */
record DashboardFrame(int left, int top, int width, DashboardFrame.Card status,
                      DashboardFrame.Card recentActivity, DashboardFrame.Card workingNode,
                      DashboardFrame.Card tasks, DashboardFrame.Card safetyVitals,
                      DashboardFrame.Card safetyWorld,
                      DashboardFrame.Card statistics,
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
    static final int MIN_ROW_H = 13;
    static final int MAX_ROW_H = 28;
    static final int BODY_TOP = 9;
    static final int MIN_CARD_H = TITLE_H + BODY_TOP + 9 + 4;
    /** A card this narrow is hard to scan, so it moves the status card above the right cards. */
    static final int MIN_CARD_W = 180;
    /** Smallest useful dashboard column while a splitter is being dragged. */
    static final int MIN_COLUMN_W = 120;
    static final int STACK_WIDTH = 420;
    /** The lower cards keep a small visible strip before the controls take the rest. */
    static final int MIN_BOTTOM_H = 40;
    /** Natural width of the three buttons plus their gaps; under it they share the row evenly. */
    static final int BUTTON_ROW_W = 258;
    static final int BUTTON_GAP = 6;
    /** Most lines any card has to offer; compact spacing makes the extra monitor fields reachable. */
    static final int MAX_ROWS = 12;

    /**
     * One status card. {@code rows} is how many of its lines the height can actually hold, so the
     * card decides what to drop rather than drawing past its own border.
     */
    record Card(int x, int y, int width, int height, int rows, int lineHeight) {

        static Card at(int x, int y, int width, int height) {
            return at(x, y, width, height, ROW_H);
        }

        static Card at(int x, int y, int width, int height, int lineHeight) {
            int safeLineHeight = Math.clamp(lineHeight, MIN_ROW_H, MAX_ROW_H);
            return new Card(x, y, width, height,
                    Math.clamp((height - TITLE_H - BODY_TOP - 4) / safeLineHeight + 1,
                            1, MAX_ROWS), safeLineHeight);
        }
    }

    static DashboardFrame of(int areaLeft, int areaTop, int areaRight, int areaBottom) {
        return of(areaLeft, areaTop, areaRight, areaBottom, ROW_H);
    }

    static DashboardFrame of(int areaLeft, int areaTop, int areaRight, int areaBottom,
                             int rowHeight) {
        return of(areaLeft, areaTop, areaRight, areaBottom, rowHeight,
                -1, -1, -1, -1, -1);
    }

    static DashboardFrame of(int areaLeft, int areaTop, int areaRight, int areaBottom,
                             int rowHeight, int topStatusWidth, int topRecentWidth,
                             int bottomTasksWidth, int bottomSafetyVitalsWidth,
                             int bottomSafetyWorldWidth) {
        int left = areaLeft + MARGIN;
        int top = areaTop + MARGIN;
        int width = Math.max(80, areaRight - MARGIN - left);
        int buttonY = areaBottom - MARGIN - HINT_H - CONTROL_H;
        Card status;
        Card recentActivity;
        Card workingNode;
        int cardsBottom;
        int safeRowHeight = Math.clamp(rowHeight, MIN_ROW_H, MAX_ROW_H);
        int budget = buttonY - GAP - MIN_BOTTOM_H - GAP - top;
        if (width >= STACK_WIDTH) {
            int height = cardHeight(budget);
            int[] topWidths = columns(width - GAP * 2, topStatusWidth, topRecentWidth);
            status = Card.at(left, top, topWidths[0], height, safeRowHeight);
            recentActivity = Card.at(left + topWidths[0] + GAP, top, topWidths[1], height,
                    safeRowHeight);
            workingNode = Card.at(left + topWidths[0] + GAP + topWidths[1] + GAP, top,
                    topWidths[2], height, safeRowHeight);
            cardsBottom = status.y() + status.height();
        } else {
            int statusHeight = cardHeight((budget - GAP) / 2);
            status = Card.at(left, top, width, statusHeight, safeRowHeight);
            int rightY = status.y() + status.height() + GAP;
            int rightHeight = Math.max(1, budget - status.height() - GAP);
            int rightWidth = Math.max(1, (width - GAP) / 2);
            recentActivity = Card.at(left, rightY, rightWidth, rightHeight, safeRowHeight);
            workingNode = Card.at(left + rightWidth + GAP, rightY,
                    Math.max(1, width - rightWidth - GAP), rightHeight, safeRowHeight);
            cardsBottom = Math.max(status.y() + status.height(),
                    rightY + rightHeight);
        }

        int bottomTop = cardsBottom + GAP;
        int bottomHeight = Math.max(1, buttonY - GAP - bottomTop);
        int[] bottomWidths = columns(width - GAP * 3, bottomTasksWidth,
                bottomSafetyVitalsWidth, bottomSafetyWorldWidth);
        Card tasks = Card.at(left, bottomTop, bottomWidths[0], bottomHeight, safeRowHeight);
        Card safetyVitals = Card.at(left + bottomWidths[0] + GAP, bottomTop, bottomWidths[1],
                bottomHeight, safeRowHeight);
        Card safetyWorld = Card.at(left + bottomWidths[0] + GAP + bottomWidths[1] + GAP,
                bottomTop, bottomWidths[2], bottomHeight, safeRowHeight);
        Card statistics = Card.at(left + bottomWidths[0] + GAP + bottomWidths[1] + GAP
                        + bottomWidths[2] + GAP, bottomTop, bottomWidths[3], bottomHeight,
                safeRowHeight);
        return new DashboardFrame(left, top, width, status, recentActivity, workingNode,
                tasks, safetyVitals, safetyWorld, statistics, buttonY, buttonY + CONTROL_H + 3,
                width < BUTTON_ROW_W);
    }

    /** Resolves draggable divider positions into positive column widths. */
    private static int[] columns(int available, int... requested) {
        int count = requested.length + 1;
        int total = Math.max(count, available);
        int minimum = Math.min(MIN_COLUMN_W, Math.max(1, total / count));
        int[] widths = new int[count];
        int remaining = total;
        for (int index = 0; index < count - 1; index++) {
            int columnsAfter = count - index - 1;
            int maximum = Math.max(minimum, remaining - minimum * columnsAfter);
            int natural = remaining / (columnsAfter + 1);
            int requestedWidth = requested[index] > 0 ? requested[index] : natural;
            widths[index] = Math.clamp(requestedWidth, minimum, maximum);
            remaining -= widths[index];
        }
        widths[count - 1] = Math.max(minimum, remaining);
        return widths;
    }

    private static int cardHeight(int available) {
        return Math.clamp(available, 1, CARD_H);
    }
}
