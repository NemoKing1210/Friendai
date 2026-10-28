package dev.friendai.features.appearance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The short content hash that names a skin on the wire and in the client cache. */
public final class SkinHash {
    public static final int LENGTH = 16;

    private SkinHash() {}

    /** First {@link #LENGTH} hex characters of the SHA-256 of the canonical PNG. */
    public static String of(byte[] png) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(png);
            return HexFormat.of().formatHex(digest).substring(0, LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    public static boolean isValid(String hash) {
        if (hash == null || hash.length() != LENGTH) {
            return false;
        }
        for (byte b : hash.getBytes(StandardCharsets.ISO_8859_1)) {
            boolean digit = b >= '0' && b <= '9';
            boolean lower = b >= 'a' && b <= 'f';
            if (!digit && !lower) {
                return false;
            }
        }
        return true;
    }
}
