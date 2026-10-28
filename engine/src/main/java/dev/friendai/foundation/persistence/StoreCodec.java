package dev.friendai.foundation.persistence;

/** JSON format for one owner's value in an {@link OwnerFileStore}. */
public interface StoreCodec<T> {
    /**
     * Decodes one owner's file.
     *
     * @param json persisted text, or {@code null} when the owner has no readable file yet;
     *             in that case a fresh empty value must be returned
     */
    T load(String json);

    /** Encodes an immutable snapshot of {@code value}. */
    String save(T value);

    /** Monotonic revision; revisions already on disk are not written again. */
    long revision(T value);

    /** Largest file size in bytes accepted before decoding. */
    long maxBytes();
}
