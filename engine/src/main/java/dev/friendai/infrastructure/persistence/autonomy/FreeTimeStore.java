package dev.friendai.infrastructure.persistence.autonomy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.agent.autonomy.FreeTime;
import dev.friendai.agent.autonomy.FreeTimeRepository;
import dev.friendai.agent.autonomy.FreeTimeSave;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.UUID;

/** Independently versioned owner free-time state. */
public final class FreeTimeStore implements FreeTimeRepository, AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    private static final long MAX_BYTES = 64 * 1024;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final OwnerFileStore<State> store;

    public FreeTimeStore(Path directory, OwnerStoreWriter writer) {
        this(OwnerFileStore.Factory.borrowing(writer), directory);
    }

    public FreeTimeStore(OwnerFileStore.Factory stores, Path directory) {
        store = stores.create(directory, new Codec());
    }

    @Override
    public FreeTime state(UUID owner) {
        return FreeTimeSave.decode(store.book(owner).payload).orElse(FreeTime.OFF);
    }

    @Override
    public void save(UUID owner, FreeTime value) {
        State state = store.book(owner);
        state.payload = FreeTimeSave.encode(value);
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
            if (file == null) throw new IllegalArgumentException("Empty free-time file.");
            if (file.formatVersion() != FORMAT_VERSION) throw new UnsupportedStoreVersionException();
            if (file.payload() != null && FreeTimeSave.decode(file.payload()).isEmpty())
                throw new IllegalArgumentException("Invalid free-time payload.");
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
