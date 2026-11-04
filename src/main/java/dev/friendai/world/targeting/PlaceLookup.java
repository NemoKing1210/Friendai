package dev.friendai.world.targeting;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.places.Place;

/** The owner's place book as the world layer needs it: a named place the companion can actually reach. */
public interface PlaceLookup {
    Place requireReachable(CompanionEntity companion, String name, String purpose);
}
