package dev.friendai.features.places;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Whether the companion may reach a target place by portal, and what still has to happen first. Pure Java: the
 * caller passes facts it read on the server thread and the adapter acts on the decision.
 *
 * <p>The only hard-coded ids are dimension ids; the supported pairs live in {@link #ROUTES}, so a new pair is a new
 * entry and an unlisted pair is refused instead of guessed. Nothing here maps coordinates or knows portal geometry:
 * {@link RouteKind} names the pair the trip uses, and an adapter that verified the game rules applies the mapping.
 *
 * <p>Checks run in a fixed order, so a caller gets one stable typed refusal: unknown dimension, same dimension,
 * unsupported pair, missing owner consent, expedition disabled, unsafe landing, and only then the portal frames.
 */
public final class PortalRoutePolicy {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String END = "minecraft:the_end";

    private static final Set<String> DIMENSIONS = Set.of(OVERWORLD, NETHER, END);

    /** The dimension pairs a portal trip may cross, and the route kind each one implies. */
    private static final Map<Set<String>, RouteKind> ROUTES =
            Map.of(Set.of(OVERWORLD, NETHER), RouteKind.NETHER, Set.of(OVERWORLD, END), RouteKind.END);

    private PortalRoutePolicy() {}

    /** The pair a trip crosses; the adapter, not this policy, owns the verified coordinate and search rules. */
    public enum RouteKind {
        /** Overworld and the Nether. */
        NETHER,
        /** Overworld and the End. */
        END
    }

    /** Whether a usable linked portal stands at that end of the trip. */
    public enum PortalLink {
        /** A usable linked portal exists; the companion can enter it. */
        LINKED,
        /** No usable linked portal exists; one would have to be built and lit. */
        MISSING
    }

    /** Why a trip is refused. Each reason names the single blocker the caller should report. */
    public enum Reason {
        /** A dimension id outside {@link #DIMENSIONS}; a modded or malformed id is never guessed. */
        UNKNOWN_DIMENSION,
        /** Source and target are the same dimension: walk there instead. */
        SAME_DIMENSION,
        /** Both dimensions are known but no portal route joins them, such as the Nether and the End. */
        UNSUPPORTED_PAIR,
        /** The owner has not agreed to the trip. */
        NO_OWNER_CONSENT,
        /** Expedition travel or destination chunk loading is off. */
        EXPEDITION_DISABLED,
        /** No safe, loadable landing exists at the destination. */
        UNSAFE_LANDING,
        /** A frame must be built but the companion does not carry obsidian and flint. */
        MISSING_MATERIAL,
        /** The trip would leave no durable recorded return anchor. */
        NO_RETURN_ANCHOR
    }

    /**
     * What the caller observed. Dimensions are normalized the way {@link #routeKind} reads them: trimmed,
     * lower-cased and namespaced, with a missing id kept empty so it is refused as unknown. Portal links must be
     * stated explicitly; a null one is a programming error, not a guess.
     */
    public record RouteFacts(
            String sourceDimension,
            String targetDimension,
            PortalLink sourcePortal,
            PortalLink targetPortal,
            boolean landingSafe,
            boolean ownerConsent,
            boolean expeditionEnabled,
            boolean portalMaterials,
            boolean returnAnchor) {

        public RouteFacts {
            sourceDimension = normalize(sourceDimension);
            targetDimension = normalize(targetDimension);
            if (sourcePortal == null) throw new IllegalArgumentException("sourcePortal");
            if (targetPortal == null) throw new IllegalArgumentException("targetPortal");
        }

        /** Facts from an adapter that has not yet checked the build materials and return anchor. */
        public RouteFacts(
                String sourceDimension,
                String targetDimension,
                PortalLink sourcePortal,
                PortalLink targetPortal,
                boolean landingSafe,
                boolean ownerConsent,
                boolean expeditionEnabled) {
            this(
                    sourceDimension,
                    targetDimension,
                    sourcePortal,
                    targetPortal,
                    landingSafe,
                    ownerConsent,
                    expeditionEnabled,
                    true,
                    true);
        }
    }

    /** The policy's answer for one trip. */
    public sealed interface Decision permits Decision.Travel, Decision.BuildAndLight, Decision.Refuse {

        /** Both ends are usable: enter the source portal and step out at the target place. */
        record Travel(RouteKind routeKind) implements Decision {
            public Travel {
                if (routeKind == null) throw new IllegalArgumentException("routeKind");
            }
        }

        /**
         * At least one end has no usable linked portal, so a frame has to be built and lit before the trip is
         * usable. Which end is missing follows from the facts the caller supplied.
         */
        record BuildAndLight() implements Decision {}

        /** The trip must not start; {@code reason} is the one blocker to report. */
        record Refuse(Reason reason) implements Decision {
            public Refuse {
                if (reason == null) throw new IllegalArgumentException("reason");
            }
        }
    }

    /** Decides whether this trip may go ahead, and what has to be built first when it may. */
    public static Decision decide(RouteFacts facts) {
        if (facts == null) throw new IllegalArgumentException("facts");
        String source = facts.sourceDimension();
        String target = facts.targetDimension();
        if (!DIMENSIONS.contains(source) || !DIMENSIONS.contains(target)) {
            return new Decision.Refuse(Reason.UNKNOWN_DIMENSION);
        }
        if (source.equals(target)) {
            return new Decision.Refuse(Reason.SAME_DIMENSION);
        }
        RouteKind kind = ROUTES.get(Set.of(source, target));
        if (kind == null) {
            return new Decision.Refuse(Reason.UNSUPPORTED_PAIR);
        }
        if (!facts.ownerConsent()) {
            return new Decision.Refuse(Reason.NO_OWNER_CONSENT);
        }
        if (!facts.expeditionEnabled()) {
            return new Decision.Refuse(Reason.EXPEDITION_DISABLED);
        }
        if (!facts.landingSafe()) {
            return new Decision.Refuse(Reason.UNSAFE_LANDING);
        }
        if (!facts.returnAnchor()) {
            return new Decision.Refuse(Reason.NO_RETURN_ANCHOR);
        }
        if (facts.sourcePortal() != PortalLink.LINKED || facts.targetPortal() != PortalLink.LINKED) {
            return facts.portalMaterials()
                    ? new Decision.BuildAndLight()
                    : new Decision.Refuse(Reason.MISSING_MATERIAL);
        }
        return new Decision.Travel(kind);
    }

    /** The route kind joining two dimensions, or empty for the same, unknown or unsupported pair. */
    public static Optional<RouteKind> routeKind(String sourceDimension, String targetDimension) {
        String source = normalize(sourceDimension);
        String target = normalize(targetDimension);
        if (source.isEmpty() || target.isEmpty() || source.equals(target)) {
            return Optional.empty();
        }
        return Optional.ofNullable(ROUTES.get(Set.of(source, target)));
    }

    /** Trims, lower-cases and namespaces an id; blank input stays empty so it is refused rather than guessed. */
    private static String normalize(String dimension) {
        if (dimension == null) return "";
        String id = dimension.strip().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return "";
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
