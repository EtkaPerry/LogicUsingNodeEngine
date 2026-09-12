package com.etka.lune.platform;

/**
 * Features that are intentionally different in a released installation. Keeping the check here
 * gives all loaders and common code the same release boundary without removing the learned policy
 * from the shipped bot.
 */
public final class BuildFeatures {

    private static final boolean RELEASE_BUILD = BuildInfo.flag("lune.release");

    private BuildFeatures() {}

    /**
     * True only for the explicitly marked release produced by the release build. A loader's
     * environment is deliberately not used here: a developer build must remain a teaching build
     * even when it is launched through a production loader.
     */
    public static boolean releaseBuild() {
        return RELEASE_BUILD;
    }

    /** Explicit approval/rejection feedback and verdicts are developer-only. */
    public static boolean approvalFeedback() {
        return !RELEASE_BUILD;
    }

    /** Only source/developer builds may write new learning data. */
    public static boolean learningWritesEnabled() {
        return !RELEASE_BUILD;
    }

    /** Detailed per-run journals are test diagnostics and are never written by a release build. */
    public static boolean runTracingEnabled() {
        return !RELEASE_BUILD;
    }
}
