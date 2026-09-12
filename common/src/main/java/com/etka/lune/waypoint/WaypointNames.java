package com.etka.lune.waypoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Names for places nobody named.
 *
 * <p>A routine that saves where it is standing usually has nothing useful to call it - the whole
 * point of the card is that it runs while nobody is watching. Refusing to save without a name makes
 * the common case the one that fails; a place called {@code Papatya} is a place you can find in the
 * list, walk to, and rename if you care.</p>
 *
 * <p>Flowers, in Turkish, because they are short, they are easy to tell apart at a glance, and a
 * list of them reads as deliberate rather than as {@code waypoint_7}.</p>
 */
public final class WaypointNames {

    private static final RandomGenerator RANDOM = new java.util.Random();

    private static final List<String> FLOWERS = List.of(
            "Lale", "Yasemin", "Papatya", "Karanfil", "Kardelen", "Gelincik", "Orkide", "Zambak",
            "Nergis", "Leylak", "Lavanta", "Gül", "Menekşe", "Sümbül", "Manolya", "Begonya",
            "Kamelya", "Fulya", "Çiğdem", "Mimoza", "Ortanca", "Açelya", "Sardunya", "Hanımeli",
            "Nilüfer", "Süsen", "Kasımpatı", "Glayöl", "Şebboy", "Krizantem", "Zerrin", "Gülhatmi");

    private WaypointNames() {}

    /** The names in the order they are offered, for anything that wants to show or test them. */
    public static List<String> flowers() {
        return FLOWERS;
    }

    /** A name none of {@code taken} is using, chosen at random. */
    public static String suggest(Collection<String> taken) {
        return suggest(taken, RANDOM);
    }

    /**
     * The same, with the randomness supplied - which is what makes this testable.
     *
     * <p>A name is only offered while nothing is using it, so the same one never comes out twice.
     * Names do run out, though, and then each flower is offered again as {@code Lale 2}. A bot
     * dropping a breadcrumb every minute is a real thing to build, and it must not start
     * failing on its thirty-third one.</p>
     */
    public static String suggest(Collection<String> taken, RandomGenerator random) {
        // equalsIgnoreCase, not a lowercased set, because that is the rule the store itself uses
        // to decide whether two names are the same one - and the two disagree on Turkish text.
        // "KASIMPATI" and "Kasimpati" lowercase apart (dotless i does not round trip) while
        // equalsIgnoreCase calls them equal, so a set would hand out a name the store then
        // overwrites.
        List<String> used = new ArrayList<>();
        for (String name : taken) {
            if (name != null) {
                used.add(name);
            }
        }

        for (int round = 1; round <= 1000; round++) {
            List<String> free = new ArrayList<>();
            for (String flower : FLOWERS) {
                String candidate = round == 1 ? flower : flower + " " + round;
                if (used.stream().noneMatch(name -> name.equalsIgnoreCase(candidate))) {
                    free.add(candidate);
                }
            }
            if (!free.isEmpty()) {
                return free.get(random.nextInt(free.size()));
            }
        }
        // Thirty-two thousand named places is not a case worth designing for, but it is a case
        // worth surviving: anything is better than saving nothing.
        return FLOWERS.get(0) + " " + (used.size() + 1);
    }
}
