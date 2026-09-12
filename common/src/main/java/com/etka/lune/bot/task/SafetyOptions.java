package com.etka.lune.bot.task;

/**
 * Which of Self Preservation's answers the player has left switched on.
 *
 * <p>The card's other parameters say <em>when</em> to act - at what air, at what health, at what
 * distance. These say <em>how</em>, and they exist because the best answer is not the same answer
 * everybody wants. A water bucket is the finest clutch in the game and it is also the bucket
 * somebody was saving for a portal. Batting a Ghast's fireball back kills the Ghast outright, and it
 * also means deliberately standing still in front of something that explodes - a reasonable thing to
 * forbid a bot doing while nobody is watching it.
 *
 * <p>Separate from the threat switches rather than folded in with them, because turning a tactic off
 * is not the same as turning the danger off: a bot with the water clutch disabled still notices the
 * fall and still reaches for a boat.
 *
 * <p>Every one defaults to on, and that is what makes them safe to add. A card saved before these
 * existed has none of them stored, and {@code CommandDef.apply} leaves a parameter absent from the
 * file at its declared default - so an old task keeps doing exactly what it always did.
 */
public record SafetyOptions(
        boolean waterClutch,
        boolean boatClutch,
        boolean cushionClutch,
        boolean answerFireballs,
        boolean buildCover
) {

    /** Everything on, which is what the card ships as and what an older saved card reads back as. */
    public static SafetyOptions all() {
        return new SafetyOptions(true, true, true, true, true);
    }
}
