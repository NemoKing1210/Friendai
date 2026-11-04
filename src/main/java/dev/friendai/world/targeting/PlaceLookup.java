package dev.friendai.world.targeting;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.places.Place;
import dev.friendai.foundation.places.PlaceValues;
import java.util.List;

/** The owner's place book as the world layer needs it: a named place the companion can actually reach. */
public interface PlaceLookup {
    Place requireReachable(CompanionEntity companion, String name, String purpose);

    /** Every place in the owner's book. */
    List<Place> all(CompanionEntity companion);

    /** A place stored under this name in this dimension, or {@code null} when the book has none. */
    Place find(CompanionEntity companion, String name, String dimension);

    /** Records a place with the owner's authority; throws {@link IllegalArgumentException} for an invalid value. */
    Place rememberAsOwner(CompanionEntity companion, PlaceValues values, long tick);
}
