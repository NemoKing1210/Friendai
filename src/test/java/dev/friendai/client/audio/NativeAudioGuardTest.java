package dev.friendai.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NativeAudioGuardTest {
    @Test
    void dedicatedServerDoesNotInvokeTheNativeLoader() {
        AtomicInteger loads = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> NativeAudioGuard.load(false, loads::incrementAndGet));

        assertEquals(0, loads.get());
    }

    @Test
    void clientEnvironmentInvokesNativeLoaderOnce() {
        AtomicInteger loads = new AtomicInteger();

        NativeAudioGuard.load(true, loads::incrementAndGet);

        assertEquals(1, loads.get());
    }

    @Test
    void dedicatedServerDoesNotConstructTheNativeEngine() {
        AtomicInteger constructions = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () -> NativeAudioGuard.create(false, () -> {
                    constructions.incrementAndGet();
                    return new Object();
                }));

        assertEquals(0, constructions.get());
    }
}
