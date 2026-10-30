package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LongMapTest {

    @Test
    void absentKeyReadsNull() {
        assertNull(new LongMap<String>().get(42L));
    }

    @Test
    void putReplacesAndKeepsSize() {
        LongMap<String> map = new LongMap<>();
        map.put(7L, "a");
        map.put(7L, "b");
        assertEquals("b", map.get(7L));
        assertEquals(1, map.size());
    }

    @Test
    void zeroAndNegativeKeysAreDistinctKeys() {
        LongMap<Integer> map = new LongMap<>();
        map.put(0L, 0);
        map.put(-1L, 1);
        map.put(Long.MIN_VALUE, 2);
        assertEquals(0, map.get(0L));
        assertEquals(1, map.get(-1L));
        assertEquals(2, map.get(Long.MIN_VALUE));
    }

    @Test
    void computeIfAbsentCreatesOnce() {
        LongMap<Object> map = new LongMap<>();
        Object first = map.computeIfAbsent(5L, k -> new Object());
        assertSame(first, map.computeIfAbsent(5L, k -> new Object()));
    }

    @Test
    void survivesGrowthAgainstReferenceMap() {
        LongMap<Long> map = new LongMap<>();
        Map<Long, Long> reference = new HashMap<>();
        Random random = new Random(1);
        for (int i = 0; i < 20_000; i++) {
            long key = Pos.pack(random.nextInt(200) - 100, random.nextInt(60), random.nextInt(200) - 100);
            map.put(key, (long) i);
            reference.put(key, (long) i);
        }
        assertEquals(reference.size(), map.size());
        reference.forEach((k, v) -> assertEquals(v, map.get(k)));
    }
}
