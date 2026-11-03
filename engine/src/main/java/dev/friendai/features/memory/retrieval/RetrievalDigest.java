package dev.friendai.features.memory.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable digests for cache keys and for the persisted derived index. {@link String#hashCode()} is deliberately not
 * used: it is 32 bits wide, so two different facts collide into one cache slot, and an in-memory hash is not a value a
 * persisted index may trust across restarts or JVMs. SHA-256 over UTF-8 is defined for every backend and produces the
 * same answer on every machine, which is what "the facts have not changed since this index was built" needs to mean.
 */
public final class RetrievalDigest {
    private RetrievalDigest() {}

    /** The first 64 bits of SHA-256 over the text, as a signed long. */
    public static long of(String text) {
        byte[] digest = sha256().digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (digest[i] & 0xffL);
        }
        return value;
    }

    /** The first 64 bits of SHA-256 over the values in order; no separator can be confused with data. */
    public static long of(long... values) {
        MessageDigest sha = sha256();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(Long.BYTES * values.length);
        for (long value : values) {
            buffer.putLong(value);
        }
        byte[] digest = sha.digest(buffer.array());
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result = (result << 8) | (digest[i] & 0xffL);
        }
        return result;
    }

    /**
     * The first 64 bits of SHA-256 over the texts in order, each length-prefixed so a list of texts cannot be confused
     * with one longer text. Used for the redacted-text digest that pins which mask an index was built under.
     */
    public static long of(java.util.List<String> parts) {
        MessageDigest sha = sha256();
        for (String part : parts) {
            byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
            sha.update(intBytes(bytes.length));
            sha.update(bytes);
        }
        byte[] digest = sha.digest();
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result = (result << 8) | (digest[i] & 0xffL);
        }
        return result;
    }

    /** A full SHA-256 digest of the parts, as lowercase hex; the length prefix keeps parts from running together. */
    public static String key(String... parts) {
        MessageDigest sha = sha256();
        for (String part : parts) {
            byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
            sha.update(intBytes(bytes.length));
            sha.update(bytes);
        }
        byte[] digest = sha.digest();
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >> 4) & 0xf, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }

    private static byte[] intBytes(int value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            // Every Java platform is required to ship SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", missing);
        }
    }
}
