package com.etka.lune;

/**
 * The places outside the game Lune points at.
 *
 * <p>Here rather than beside the screens that use them because they are the same three addresses
 * wherever they appear - the terms screen offers the license, the About tab offers all three - and
 * a repository that moves should move once. Everything is derived from {@link #REPOSITORY} for the
 * same reason.</p>
 */
public final class Links {

    public static final String REPOSITORY = "https://github.com/EtkaPerry/LogicUsingNodeEngine";
    /**
     * The license in full. Bundled in the jar as well, but a path inside a jar is not something a
     * player can go and read while a screen is asking them to accept it.
     */
    public static final String LICENSE = REPOSITORY + "/blob/main/LICENSE";
    public static final String ISSUES = REPOSITORY + "/issues";

    private Links() {}
}
