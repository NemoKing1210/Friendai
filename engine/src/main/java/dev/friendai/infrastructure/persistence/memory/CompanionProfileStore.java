package dev.friendai.infrastructure.persistence.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.memory.CompanionDescription;
import dev.friendai.features.memory.CompanionName;
import dev.friendai.features.memory.CompanionProfile;
import dev.friendai.features.memory.CompanionProfileRepository;
import dev.friendai.features.memory.OwnerProfile;
import dev.friendai.foundation.persistence.StoreCodec;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import dev.friendai.foundation.persistence.UnsupportedStoreVersionException;
import dev.friendai.infrastructure.persistence.OwnerFileStore;
import dev.friendai.infrastructure.persistence.OwnerStoreWriter;
import java.nio.file.Path;
import java.util.UUID;

/** Independent owner profile files. */
public final class CompanionProfileStore implements CompanionProfileRepository, AutoCloseable {
    public static final int FORMAT_VERSION = 1;
    private static final long MAX_FILE_BYTES = 64 * 1024;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final OwnerFileStore<ProfileState> store;

    public CompanionProfileStore(Path directory, OwnerStoreWriter writer) {
        this(OwnerFileStore.Factory.borrowing(writer), directory);
    }

    public CompanionProfileStore(OwnerFileStore.Factory stores, Path directory) {
        store = stores.create(directory, new Codec());
    }

    @Override
    public CompanionProfile profile(UUID owner) {
        ProfileFile file = store.book(owner).record;
        String name = CompanionName.parse(file.name()).orElse(null);
        CompanionDescription description;
        OwnerProfile ownerProfile;
        try {
            description = new CompanionDescription(file.summary(), file.details());
        } catch (IllegalArgumentException invalid) {
            description = CompanionDescription.EMPTY;
        }
        try {
            ownerProfile = new OwnerProfile(file.language(), file.address());
        } catch (IllegalArgumentException invalid) {
            ownerProfile = OwnerProfile.EMPTY;
        }
        return new CompanionProfile(name, description, ownerProfile);
    }

    @Override
    public void save(UUID owner, CompanionProfile profile) {
        ProfileState state = store.book(owner);
        ProfileFile previous = state.record;
        state.record = new ProfileFile(
                FORMAT_VERSION,
                previous.revision() + 1,
                profile.name(),
                profile.description().summary(),
                profile.description().details(),
                profile.ownerProfile().language(),
                profile.ownerProfile().address());
    }

    @Override
    public long revision(UUID owner) {
        return store.book(owner).record.revision();
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

    private record ProfileFile(
            int formatVersion,
            long revision,
            String name,
            String summary,
            String details,
            String language,
            String address) {
        static ProfileFile empty() {
            return new ProfileFile(FORMAT_VERSION, 0, null, "", "", "auto", "");
        }
    }

    private static final class ProfileState {
        private ProfileFile record = ProfileFile.empty();
    }

    private static final class Codec implements StoreCodec<ProfileState> {
        @Override
        public ProfileState load(String json) {
            ProfileState state = new ProfileState();
            if (json == null) return state;
            ProfileFile file = GSON.fromJson(json, ProfileFile.class);
            if (file == null) throw new IllegalArgumentException("Empty profile file.");
            if (file.formatVersion() != FORMAT_VERSION) throw new UnsupportedStoreVersionException();
            state.record = file;
            return state;
        }

        @Override
        public String save(ProfileState value) {
            return GSON.toJson(value.record);
        }

        @Override
        public long revision(ProfileState value) {
            return value.record.revision();
        }

        @Override
        public long maxBytes() {
            return MAX_FILE_BYTES;
        }
    }
}
