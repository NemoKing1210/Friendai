package dev.friendai.infrastructure.persistence;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One bounded daemon writer per server runtime. Stores borrow it and report their own submission failures. */
public final class OwnerStoreWriter implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/store");
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private boolean closed;

    public OwnerStoreWriter() {
        this(256);
    }

    public OwnerStoreWriter(int capacity) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "friendai-store-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor = pool;
        ownedExecutor = pool;
    }

    /** Package-local deterministic scheduling seam; the supplied executor remains borrowed. */
    OwnerStoreWriter(Executor executor) {
        this.executor = Objects.requireNonNull(executor);
        ownedExecutor = null;
    }

    public synchronized boolean trySubmit(Runnable write) {
        Objects.requireNonNull(write);
        if (closed) return false;
        try {
            executor.execute(write);
            return true;
        } catch (RejectedExecutionException failure) {
            return false;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (ownedExecutor == null) return;
            ownedExecutor.shutdown();
        }
        try {
            if (!ownedExecutor.awaitTermination(30, TimeUnit.SECONDS))
                LOGGER.warn("Owner store writer did not stop within 30 seconds");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
