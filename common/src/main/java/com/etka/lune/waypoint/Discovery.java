package com.etka.lune.waypoint;

/**
 * Somewhere a compass once pointed: the biome or structure it was asked for, and the column it
 * answered with.
 *
 * <p>Kept apart from {@link Waypoint} on purpose. A waypoint is a name the player chose, looked
 * up by that name; a discovery is looked up by what it is - {@code biome} plus
 * {@code minecraft:jungle} plus the dimension - so a card asking for a jungle finds the one
 * already found without anybody having named anything, and in any language. Compasses answer in
 * columns, so there is no height.</p>
 *
 * @param kind      {@code biome} or {@code structure}
 * @param id        the registry id the compass was asked for
 * @param dimension the dimension id, in the form a {@link Waypoint} stores
 * @param foundAt   wall-clock milliseconds, so the list can be read as a history
 */
public record Discovery(String kind, String id, String dimension, int x, int z, long foundAt) {

    public static final String BIOME = "biome";
    public static final String STRUCTURE = "structure";

    /** What a discovery is looked up by. */
    public String key() {
        return key(kind, id, dimension);
    }

    public static String key(String kind, String id, String dimension) {
        return kind + "|" + id + "|" + dimension;
    }

    /**
     * A registry path as a title: {@code ancient_city} becomes {@code Ancient City}. For the
     * structures, which the game has no names for; biomes ask the game first.
     */
    public static String humanize(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder out = new StringBuilder(path.length());
        boolean startOfWord = true;
        for (char c : path.toCharArray()) {
            if (c == '_' || c == '/' || c == '.') {
                out.append(' ');
                startOfWord = true;
            } else {
                out.append(startOfWord ? Character.toUpperCase(c) : c);
                startOfWord = false;
            }
        }
        String title = out.toString().trim();
        return title.isEmpty() ? id : title;
    }
}
