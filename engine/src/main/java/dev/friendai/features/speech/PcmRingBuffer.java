package dev.friendai.features.speech;

import java.util.Arrays;

/**
 * A bounded, overwrite-oldest PCM byte ring for volatile pre-roll. It never grows beyond its capacity, never
 * persists, and returns copies so a consumer cannot mutate the captured buffer.
 */
public final class PcmRingBuffer {
    private final byte[] data;
    private int head;
    private int size;

    public PcmRingBuffer(int capacityBytes) {
        if (capacityBytes < 1) throw new IllegalArgumentException("capacity");
        this.data = new byte[capacityBytes];
    }

    public synchronized void write(byte[] source, int length) {
        if (source == null || length <= 0) return;
        int count = Math.min(length, source.length);
        if (count >= data.length) {
            System.arraycopy(source, count - data.length, data, 0, data.length);
            head = 0;
            size = data.length;
            return;
        }
        for (int i = 0; i < count; i++) {
            data[(head + size) % data.length] = source[i];
            if (size < data.length) size++;
            else head = (head + 1) % data.length;
        }
    }

    public synchronized byte[] snapshot() {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) out[i] = data[(head + i) % data.length];
        return out;
    }

    public synchronized void clear() {
        Arrays.fill(data, (byte) 0);
        head = 0;
        size = 0;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return data.length;
    }
}
