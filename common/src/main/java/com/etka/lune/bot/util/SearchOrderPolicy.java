package com.etka.lune.bot.util;

/**
 * Pure rules for ordering equally good search candidates; kept testable without Minecraft.
 *
 * <p>Distance decides almost every pick, and where it does not, something has to. Inside a solid
 * body of the wanted block - a modded deepslate mountain, a clay bank, a fat ore vein - every
 * neighbour of the block just mined is exactly one away, so the tie-break stops being a detail and
 * becomes the entire decision about which way the bot eats through the deposit.
 */
public final class SearchOrderPolicy {

    private SearchOrderPolicy() {}

    /**
     * Which side of the work a candidate is on, lowest first, for use as a tie-break after distance.
     *
     * <p>The order is level, then above, then below. It used to be insertion order, and the block
     * scan fills chunk sections from the bottom up, so the block underneath won every tie: told to
     * collect deepslate from the top of a mountain, the bot sank a one-wide shaft straight down
     * through it rather than working across the face, and buried itself doing it.
     *
     * <p>Down last is also what a person does. You clear what is in front of you, you take what is
     * above while you can still reach it, and you only dig under your own feet when there is
     * nothing else left - because that is the one direction that costs you the ground you stand on.
     */
    public static int verticalPreference(int originY, int candidateY) {
        if (candidateY == originY) {
            return 0;
        }
        return candidateY > originY ? 1 : 2;
    }
}
