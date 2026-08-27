package com.etka.lune.bot.input;

/**
 * The virtual keyboard the bot "presses". Tasks and the path executor write to this each tick;
 * {@link BotClientInput} reads it and feeds it to the player.
 */
public final class BotInput {

    public boolean forward;
    public boolean backward;
    public boolean left;
    public boolean right;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;

    /** Cleared at the start of every bot tick so stale presses can never stick. */
    public void reset() {
        forward = backward = left = right = jump = sneak = sprint = false;
    }
}
