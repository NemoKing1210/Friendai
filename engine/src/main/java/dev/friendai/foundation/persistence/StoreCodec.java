package dev.friendai.foundation.persistence;

import java.io.IOException;
import java.io.Reader;

/** JSON format for one owner's value. */
public interface StoreCodec<T> {
    /**
     * Decodes one owner's file.
     *
     * @param json persisted text, or {@code null} when the owner has no readable file yet;
     *             in that case a fresh empty value must be returned
     */
    T load(String json);

    /** Bounded text decoding; streaming codecs may override this without buffering the whole document. */
    default T load(Reader reader) throws IOException {
        StringBuilder json = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if ((long) json.length() + count > maxBytes()) throw new IOException("Store input exceeds limit.");
            json.append(buffer, 0, count);
        }
        return load(json.toString());
    }

    /** Encodes an immutable snapshot of {@code value}. */
    String save(T value);

    /** Monotonic revision; revisions already on disk are not written again. */
    long revision(T value);

    /** Largest file size in bytes accepted before decoding. */
    long maxBytes();
}
