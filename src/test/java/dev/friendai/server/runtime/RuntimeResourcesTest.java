package dev.friendai.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeResourcesTest {

    @Test
    void closesOwnedResourcesAndActionsInReverseAcquisitionOrderOnlyOnce() {
        RuntimeResources resources = new RuntimeResources(ignored -> {});
        List<String> closed = new ArrayList<>();
        resources.own(closeAction("first", closed));
        resources.onClose(() -> closed.add("second"));
        resources.own(closeAction("third", closed));

        resources.close();
        resources.close();

        assertEquals(List.of("third", "second", "first"), closed);
    }

    @Test
    void constructionFailureClosesEverythingAlreadyAcquiredAndRethrowsFailure() {
        List<String> closed = new ArrayList<>();
        RuntimeResources resources = new RuntimeResources(ignored -> {});
        IllegalStateException failure = new IllegalStateException("construction failed");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> resources.construct(() -> {
                    resources.own(closeAction("first", closed));
                    resources.onClose(() -> closed.add("second"));
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(List.of("second", "first"), closed);
    }

    @Test
    void aFailingCloseIsReportedAndDoesNotSkipRemainingResources() {
        List<String> closed = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        RuntimeException failure = new RuntimeException("close failed");
        RuntimeResources resources = new RuntimeResources(failures::add);
        resources.onClose(() -> closed.add("first"));
        resources.onClose(() -> {
            closed.add("failing");
            throw failure;
        });
        resources.onClose(() -> closed.add("last"));

        resources.close();

        assertEquals(List.of("last", "failing", "first"), closed);
        assertEquals(List.of(failure), failures);
    }

    @Test
    void borrowedResourcesAreReturnedButNeverClosed() {
        RuntimeResources resources = new RuntimeResources(ignored -> {});
        AtomicInteger closes = new AtomicInteger();
        AutoCloseable borrowed = closes::incrementAndGet;

        assertSame(borrowed, resources.borrowed(borrowed));
        resources.close();

        assertEquals(0, closes.get());
    }

    private static AutoCloseable closeAction(String name, List<String> closed) {
        return () -> closed.add(name);
    }
}
