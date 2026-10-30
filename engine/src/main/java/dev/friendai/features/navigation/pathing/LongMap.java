package dev.friendai.features.navigation.pathing;

import java.util.function.LongFunction;

/**
 * An open-addressing map from packed positions to values, for the search hot path: no boxed keys and no entry
 * objects. Keys are never removed, which is all a search needs. Not thread-safe.
 */
public final class LongMap<V> {

    private static final int MIN_CAPACITY = 16;

    private long[] keys;
    private Object[] values;
    private boolean[] used;
    private int size;

    public LongMap() {
        this(MIN_CAPACITY);
    }

    public LongMap(int expected) {
        int capacity = MIN_CAPACITY;
        while (capacity < expected * 2) {
            capacity <<= 1;
        }
        keys = new long[capacity];
        values = new Object[capacity];
        used = new boolean[capacity];
    }

    public int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        int mask = keys.length - 1;
        for (int i = slot(key, mask); used[i]; i = (i + 1) & mask) {
            if (keys[i] == key) {
                return (V) values[i];
            }
        }
        return null;
    }

    public void put(long key, V value) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        insert(key, value);
    }

    /** The value for {@code key}, created and stored by {@code factory} when absent. */
    public V computeIfAbsent(long key, LongFunction<? extends V> factory) {
        V existing = get(key);
        if (existing != null) {
            return existing;
        }
        V created = factory.apply(key);
        put(key, created);
        return created;
    }

    private void insert(long key, Object value) {
        int mask = keys.length - 1;
        int i = slot(key, mask);
        while (used[i]) {
            if (keys[i] == key) {
                values[i] = value;
                return;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        size++;
    }

    private void grow() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        boolean[] oldUsed = used;
        int capacity = oldKeys.length << 1;
        keys = new long[capacity];
        values = new Object[capacity];
        used = new boolean[capacity];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                insert(oldKeys[i], oldValues[i]);
            }
        }
    }

    /** Packed positions have structured low bits, so mix them before masking. */
    private static int slot(long key, int mask) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32) & mask;
    }
}
