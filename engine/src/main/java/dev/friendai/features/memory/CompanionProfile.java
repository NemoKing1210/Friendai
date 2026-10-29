package dev.friendai.features.memory;

import java.util.Objects;

/** The owner's companion identity and communication preferences. */
public record CompanionProfile(String name, CompanionDescription description, OwnerProfile ownerProfile) {
    public static final CompanionProfile EMPTY =
            new CompanionProfile(null, CompanionDescription.EMPTY, OwnerProfile.EMPTY);

    public CompanionProfile {
        description = Objects.requireNonNullElse(description, CompanionDescription.EMPTY);
        ownerProfile = Objects.requireNonNullElse(ownerProfile, OwnerProfile.EMPTY);
    }
}
