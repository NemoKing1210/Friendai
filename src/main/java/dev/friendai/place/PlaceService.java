package dev.friendai.place;

import dev.friendai.config.ServerConfig;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.Pos;
import dev.friendai.perception.RelativeDirection;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only Minecraft-aware class of the place package: the server-thread facade that tools, the task hook, the
 * prompt and the menu go through. Every method runs on the server thread, resolves the owner's book from the
 * store and hands the actual work to the pure classes.
 *
 * <p>Rights are the tool layer's business ({@link AccessLevel} checks live in the tool definitions); the caller
 * here decides the author of a remembered place and the guest gate of the prompt block, and the mutators accept
 * it so every entry point takes the same principal.
 */
public final class PlaceService {

    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/places");

    private PlaceService() {}

    /** The places of the companion's owner; a companion without an owner has none. */
    public static PlaceRegistry book(CompanionEntity companion) {
        return state(companion).places().book(owner(companion));
    }

    /**
     * Creates or updates a place, stamping its author as {@code caller.uuid()} when the turn has a player and the
     * owner otherwise (a plan step has no player). A new place is refused once the owner's book is at
     * {@code places.maxPlaces}; an update always passes.
     */
    public static Place remember(CompanionEntity companion, Caller caller, PlaceValues values, long tick) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(caller, "caller");
        PlaceRegistry book = book(companion);
        UUID author = caller.uuid() != null ? caller.uuid() : owner(companion);
        if (book.find(values.name(), values.dimension()).isEmpty()) {
            int maxPlaces = config(companion).places.maxPlaces;
            if (book.all().size() >= maxPlaces) {
                throw new IllegalArgumentException(
                        "no room for more places (" + maxPlaces + "); forget one first");
            }
        }
        PlaceValues stored = new PlaceValues(
                values.name(), values.kind(), values.label(), values.dimension(), values.anchor(), values.radius(),
                values.aliases(), values.source(), author, values.note());
        return book.remember(stored, tick);
    }

    /** Answers "which places do I know?" from the companion's own position and dimension. */
    public static List<PlaceQuery.Result> find(CompanionEntity companion, PlaceQuery.Request request) {
        Objects.requireNonNull(request, "request");
        return PlaceQuery.find(book(companion), request, self(companion), dimension(companion));
    }

    /** Renames the one place {@code name} (or an alias) points at, keeping its id and aliases. */
    public static Place rename(
            CompanionEntity companion, Caller caller, String name, String newName, long tick) {
        Place place = unique(companion, name);
        return book(companion).rename(place.id(), newName, tick);
    }

    /** Forgets the one place {@code name} (or an alias) points at; false when it was already gone. */
    public static boolean forget(CompanionEntity companion, Caller caller, String name) {
        Place place = unique(companion, name);
        return book(companion).remove(place.id());
    }

    /**
     * The system-prompt block for this caller, or {@code ""} when places are off, the caller is below
     * {@link AccessLevel#TRUSTED}, or the book holds nothing. Same-dimension places show distance and compass
     * side; the rest show their dimension.
     */
    public static String promptBlock(CompanionEntity companion, Caller caller) {
        ServerConfig config = config(companion);
        if (!config.places.enabled || !caller.access().allows(AccessLevel.TRUSTED)) {
            return "";
        }
        BlockPos origin = companion.blockPosition();
        String dimension = dimension(companion);
        List<PlaceQuery.Result> results = PlaceQuery.find(
                book(companion),
                new PlaceQuery.Request(
                        Optional.empty(), Set.of(), Optional.empty(), config.places.promptLimit, false),
                new Pos(origin.getX(), origin.getY(), origin.getZ()),
                dimension);
        if (results.isEmpty()) {
            return "";
        }
        List<PlaceText.Line> lines = new ArrayList<>(results.size());
        for (PlaceQuery.Result result : results) {
            Place place = result.place();
            String where = result.sameDimension()
                    ? Math.round(result.distance()) + " blocks " + RelativeDirection.compass(
                            place.anchor().x() - origin.getX(), place.anchor().z() - origin.getZ())
                    : place.dimension();
            lines.add(new PlaceText.Line(place, where));
        }
        return PlaceText.prompt(lines, config.places.promptBudget);
    }

    /**
     * Applies the pure {@link PlaceAutoRegistration} decision for a place a task noticed, gated by
     * {@code places.enabled} and {@code places.autoRemember}. A rejected place (the owner's book is full) is
     * logged and forgotten: it must never fail the task that found it.
     */
    public static void autoRegister(CompanionEntity companion, PlaceCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        ServerConfig config = config(companion);
        if (!config.places.enabled || !config.places.autoRemember) {
            return;
        }
        long tick = companion.level().getGameTime();
        try {
            PlaceAutoRegistration.Outcome outcome = PlaceAutoRegistration.decide(
                    book(companion), candidate, config.places.mergeRadius, tick);
            if (outcome.decision() == PlaceAutoRegistration.Decision.SKIP) {
                return;
            }
            PlaceValues values = new PlaceValues(
                    outcome.name(), candidate.kind(), candidate.label(), candidate.dimension(),
                    candidate.anchor(), candidate.radius(), Set.of(), candidate.source(), owner(companion), "");
            remember(companion, new Caller(null, "", AccessLevel.OWNER), values, tick);
        } catch (IllegalArgumentException rejected) {
            LOGGER.debug("Place not remembered: {}", rejected.getMessage());
        }
    }

    /** Counts a visit; a place forgotten since it was resolved is simply left alone. */
    public static void markVisit(CompanionEntity companion, long placeId, long tick) {
        PlaceRegistry book = book(companion);
        if (book.byId(placeId).isPresent()) {
            book.visit(placeId, tick);
        }
    }

    /**
     * The one place whose name or alias matches, within the companion's dimension. Zero matches and several
     * matches both throw a message the model can act on, so an ambiguous name is never resolved by a guess.
     */
    public static Optional<Place> requireSingle(CompanionEntity companion, String name) {
        return Optional.of(unique(companion, name));
    }

    /**
     * The one place this name or alias points at in the companion's dimension. A place remembered in another
     * dimension is reported as such, naming {@code what} as the action that cannot cross, so callers never
     * have to repeat that message; any other failure keeps the lookup's own message.
     */
    public static Place requireReachable(CompanionEntity companion, String name, String what) {
        Objects.requireNonNull(what, "what");
        Optional<Place> here;
        try {
            here = requireSingle(companion, name);
        } catch (IllegalArgumentException unknownOrAmbiguous) {
            Optional<Place> elsewhere = elsewhere(companion, name);
            if (elsewhere.isEmpty()) {
                throw unknownOrAmbiguous;
            }
            throw new IllegalArgumentException("\"" + name.strip() + "\" is in "
                    + elsewhere.get().dimension() + "; " + what + " cannot cross dimensions");
        }
        return here.orElseThrow();
    }

    /** The place matching this name or alias in another dimension, if one exists. */
    private static Optional<Place> elsewhere(CompanionEntity companion, String name) {
        String needle;
        try {
            needle = PlaceNames.normalize(name);
        } catch (IllegalArgumentException notAName) {
            return Optional.empty();
        }
        String current = dimension(companion);
        for (Place place : book(companion).all()) {
            if (!place.dimension().equals(current) && matches(place, needle)) {
                return Optional.of(place);
            }
        }
        return Optional.empty();
    }

    private static boolean matches(Place place, String needle) {
        if (PlaceNames.normalize(place.name()).equals(needle)) {
            return true;
        }
        for (String alias : place.aliases()) {
            if (PlaceNames.normalize(alias).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Place unique(CompanionEntity companion, String name) {
        String dimension = dimension(companion);
        String needle = PlaceNames.normalize(name);
        List<Place> hits = new ArrayList<>();
        for (Place place : book(companion).all()) {
            if (!place.dimension().equals(dimension)) {
                continue;
            }
            if (matches(place, needle)) {
                hits.add(place);
            }
        }
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("no place called " + name + "; use find_place");
        }
        if (hits.size() > 1) {
            List<String> names = new ArrayList<>(hits.size());
            for (Place place : hits) {
                names.add(place.name());
            }
            throw new IllegalArgumentException("several places match " + name + ": "
                    + String.join(", ", names) + "; give the exact name");
        }
        return hits.get(0);
    }

    private static Pos self(CompanionEntity companion) {
        BlockPos origin = companion.blockPosition();
        return new Pos(origin.getX(), origin.getY(), origin.getZ());
    }

    private static String dimension(CompanionEntity companion) {
        return level(companion).dimension().identifier().toString();
    }

    private static UUID owner(CompanionEntity companion) {
        UUID owner = companion.ownerUuid();
        if (owner == null) {
            throw new IllegalArgumentException("The companion has no owner, so it has no places.");
        }
        return owner;
    }

    private static ServerConfig config(CompanionEntity companion) {
        return state(companion).config();
    }

    private static FriendaiServerState state(CompanionEntity companion) {
        return FriendaiServerState.get(level(companion).getServer());
    }

    private static ServerLevel level(CompanionEntity companion) {
        if (companion.level() instanceof ServerLevel level) {
            return level;
        }
        throw new IllegalArgumentException("Places are unavailable outside a server world.");
    }
}
