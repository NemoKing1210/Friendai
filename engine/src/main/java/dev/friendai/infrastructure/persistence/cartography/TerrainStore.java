package dev.friendai.infrastructure.persistence.cartography;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.cartography.TerrainMapSave;
import dev.friendai.features.cartography.TerrainRepository;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.UUID;

/** Independently versioned per-owner terrain grid; a missing/corrupt/newer record reads as empty. */
public final class TerrainStore implements TerrainRepository, AutoCloseable {
    public static final int FORMAT_VERSION = 2;
    private static final long MAX_BYTES = 512 * 1024;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final OwnerFileStore<State> store;

    public TerrainStore(Path directory, OwnerStoreWriter writer) {
        this(OwnerFileStore.Factory.borrowing(writer), directory);
    }

    public TerrainStore(OwnerFileStore.Factory stores, Path directory) {
        store = stores.create(directory, new Codec());
    }

    @Override
    public TerrainGrid grid(UUID owner) {
        try {
            return TerrainMapSave.decode(store.book(owner).payload).orElseGet(TerrainGrid::new);
        } catch (UnsupportedStoreVersionException newer) {
            return new TerrainGrid();
        }
    }

    @Override
    public void save(UUID owner, TerrainGrid value) {
        String payload = TerrainMapSave.encode(value);
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

    private static final class Codec implements StoreCodec<State> {
        @Override
        public State load(String json) {
            State state = new State();
            if (json == null) return state;
            File file = GSON.fromJson(json, File.class);
            if (file == null) throw new IllegalArgumentException("Empty terrain file.");
            if (file.formatVersion() != FORMAT_VERSION) throw new UnsupportedStoreVersionException();
            if (file.payload() != null && TerrainMapSave.decode(file.payload()).isEmpty()) {
                throw new IllegalArgumentException("Invalid terrain payload.");
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
