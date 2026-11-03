package dev.friendai.infrastructure.persistence.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.storage.StorageIndex;
import dev.friendai.features.storage.StorageLimits;
import dev.friendai.features.storage.StorageMapSave;
import dev.friendai.features.storage.StorageRepository;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Independently versioned per-owner container index; a missing/corrupt/newer record reads as empty. */
public final class StorageStore implements StorageRepository, AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    private static final long MAX_BYTES = 512 * 1024;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final OwnerFileStore<State> store;
    private final Supplier<StorageLimits> limits;

    public StorageStore(Path directory, Supplier<StorageLimits> limits, OwnerStoreWriter writer) {
        this(OwnerFileStore.Factory.borrowing(writer), directory, limits);
    }

    public StorageStore(OwnerFileStore.Factory stores, Path directory, Supplier<StorageLimits> limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.store = stores.create(directory, new Codec());
    }

    @Override
    public StorageIndex index(UUID owner) {
        try {
            return StorageMapSave.decode(store.book(owner).payload, limits.get())
                    .orElseGet(() -> new StorageIndex(limits.get()));
        } catch (UnsupportedStoreVersionException newer) {
            return new StorageIndex(limits.get());
        }
    }

    @Override
    public void save(UUID owner, StorageIndex index) {
        String payload = StorageMapSave.encode(index);
        State state;
        try {
            state = store.book(owner);
        } catch (UnsupportedStoreVersionException newer) {
            return;
        }
        if (payload.equals(state.payload)) {
            return;
        }
        state.payload = payload;
        state.revision++;
    }

    public StoreWriteStatus status(UUID owner) {
        return store.status(owner);
    }

    public void flushIfDue() {
        store.flushIfDue();
    }

    public void flushAll() {
        store.flushAll();
    }

    @Override
    public void close() {
        store.close();
    }

    private record File(int formatVersion, long revision, String payload) {}

    private static final class State {
        private long revision;
        private String payload;

        private File snapshot() {
            return new File(FORMAT_VERSION, revision, payload);
        }
    }

    private final class Codec implements StoreCodec<State> {
        @Override
        public State load(String json) {
            State state = new State();
            if (json == null) {
                return state;
            }
            File file = GSON.fromJson(json, File.class);
            if (file == null) {
                throw new IllegalArgumentException("Empty storage file.");
            }
            if (file.formatVersion() != FORMAT_VERSION) {
                throw new UnsupportedStoreVersionException();
            }
            if (file.payload() != null
                    && StorageMapSave.decode(file.payload(), limits.get()).isEmpty()) {
                throw new IllegalArgumentException("Invalid storage payload.");
            }
            state.revision = Math.max(0, file.revision());
            state.payload = file.payload();
            return state;
        }

        @Override
        public String save(State value) {
            return GSON.toJson(value.snapshot());
        }

        @Override
        public long revision(State value) {
            return value.revision;
        }

        @Override
        public long maxBytes() {
            return MAX_BYTES;
        }
    }
}
