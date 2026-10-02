package com.etka.lune.config;

/**
 * The rules for share links, at {@code lunode.etka.co.uk/policy}: what may go in a shared task,
 * who can see and change it, and that it is deleted after thirty days without being opened.
 *
 * <p>Agreed to once, in the Share popup, by pressing "Agree and create link" - the button says
 * what pressing it means, beside a button that opens the policy. After that the popup only
 * reminds. Raise {@link #VERSION} when the policy changes in a way a player should see before
 * their next link, and the popup asks again; the page carries the same date.</p>
 *
 * <p>Separate from {@link Terms}: those are the mod's own, agreed to before the panel opens at all.
 * A player who never shares a task never needs to read this one.</p>
 */
public final class SharePolicy {

    /** The policy text this build asks the player to agree to. */
    public static final int VERSION = 1;

    private SharePolicy() {}

    public static boolean accepted() {
        return accepted(BotConfig.get().acceptedSharePolicyVersion);
    }

    /** The rule itself, apart from where the number is stored, so it can be tested on its own. */
    public static boolean accepted(int acceptedVersion) {
        return acceptedVersion >= VERSION;
    }

    /** Records agreement and writes it straight out, as {@link Terms#accept()} does. */
    public static void accept() {
        BotConfig config = BotConfig.get();
        config.acceptedSharePolicyVersion = VERSION;
        config.save();
    }
}
