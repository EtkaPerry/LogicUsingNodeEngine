package com.etka.lune.games;

import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * What a game has dealt, so it never deals the same thing twice: 32-bit fingerprints, four bytes
 * each, one after another, packed into one string.
 *
 * <p>URL-safe base64 without padding, so a history sits on one line of the config. A list of
 * numbers would put each on a line of its own, and Gson writes a padding {@code =} as an escape. A
 * history that no longer reads is forgotten rather than failing a deal.</p>
 */
public final class DealtHistory {

    private static final int BYTES = Integer.BYTES;

    private DealtHistory() {}

    /** Whether {@code packed} holds {@code fingerprint}. */
    public static boolean contains(String packed, int fingerprint) {
        ByteBuffer bytes = ByteBuffer.wrap(unpacked(packed));
        while (bytes.remaining() >= BYTES) {
            if (bytes.getInt() == fingerprint) {
                return true;
            }
        }
        return false;
    }

    /** {@code packed} with {@code fingerprint} added last, and the oldest let go past {@code cap}. */
    public static String appended(String packed, int fingerprint, int cap) {
        byte[] held = unpacked(packed);
        int count = held.length / BYTES;
        int keep = Math.clamp(count, 0, Math.max(0, cap - 1));
        ByteBuffer out = ByteBuffer.allocate((keep + 1) * BYTES);
        out.put(held, (count - keep) * BYTES, keep * BYTES);
        out.putInt(fingerprint);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array());
    }

    /** How many fingerprints {@code packed} holds. */
    public static int size(String packed) {
        return unpacked(packed).length / BYTES;
    }

    private static byte[] unpacked(String packed) {
        if (packed == null || packed.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getUrlDecoder().decode(packed);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
