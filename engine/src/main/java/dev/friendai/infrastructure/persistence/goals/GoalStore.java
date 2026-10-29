package dev.friendai.infrastructure.persistence.goals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.agent.goal.GoalBoard;
import dev.friendai.agent.goal.GoalRepository;
import dev.friendai.agent.goal.GoalSave;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Independently versioned owner goal boards. */
public final class GoalStore implements GoalRepository, AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    private static final long MAX_BYTES = 64 * 1024;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final OwnerFileStore<State> store;
    private final Map<UUID, GoalBoard> boards = new HashMap<>();

    public GoalStore(Path directory, OwnerStoreWriter writer) {
        this(OwnerFileStore.Factory.borrowing(writer), directory);
    }

    public GoalStore(OwnerFileStore.Factory stores, Path directory) {
        store = stores.create(directory, new Codec());
    }

    @Override
    public GoalBoard board(UUID owner, int maxBackground, int maxReplans) {
        return boards.computeIfAbsent(
                owner,
                id -> GoalSave.decodePayload(store.book(id).payload, maxBackground, maxReplans)
                        .orElseGet(() -> new GoalBoard(maxBackground, maxReplans)));
    }

    @Override
    public void save(UUID owner, GoalBoard board) {
        State state = store.book(owner);
        state.payload = board.all().isEmpty() ? null : GoalSave.encodePayload(board);
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
            if (file == null) throw new IllegalArgumentException("Empty goal file.");
            if (file.formatVersion() != FORMAT_VERSION) throw new UnsupportedStoreVersionException();
            if (file.payload() != null
                    && GoalSave.decodePayload(file.payload(), Integer.MAX_VALUE, Integer.MAX_VALUE)
                            .isEmpty()) throw new IllegalArgumentException("Invalid goal payload.");
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
