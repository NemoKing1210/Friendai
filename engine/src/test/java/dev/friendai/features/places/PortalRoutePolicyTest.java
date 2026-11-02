package dev.friendai.features.places;

import static dev.friendai.features.places.PortalRoutePolicy.END;
import static dev.friendai.features.places.PortalRoutePolicy.NETHER;
import static dev.friendai.features.places.PortalRoutePolicy.OVERWORLD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.places.PortalRoutePolicy.Decision;
import dev.friendai.features.places.PortalRoutePolicy.PortalLink;
import dev.friendai.features.places.PortalRoutePolicy.Reason;
import dev.friendai.features.places.PortalRoutePolicy.RouteFacts;
import dev.friendai.features.places.PortalRoutePolicy.RouteKind;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PortalRoutePolicyTest {

    private static RouteFacts facts(String source, String target, PortalLink sourcePortal, PortalLink targetPortal) {
        return new RouteFacts(source, target, sourcePortal, targetPortal, true, true, true);
    }

    @Test
    void linkedAndEnabledTravelsOverTheNetherRoute() {
        Decision decision = PortalRoutePolicy.decide(facts(OVERWORLD, NETHER, PortalLink.LINKED, PortalLink.LINKED));
        assertEquals(new Decision.Travel(RouteKind.NETHER), decision);
    }

    @Test
    void linkedAndEnabledTravelsBackOutOfTheNether() {
        Decision decision = PortalRoutePolicy.decide(facts(NETHER, OVERWORLD, PortalLink.LINKED, PortalLink.LINKED));
        assertEquals(new Decision.Travel(RouteKind.NETHER), decision);
    }

    @Test
    void linkedAndEnabledTravelsOverTheEndRoute() {
        Decision decision = PortalRoutePolicy.decide(facts(OVERWORLD, END, PortalLink.LINKED, PortalLink.LINKED));
        assertEquals(new Decision.Travel(RouteKind.END), decision);
    }

    @Test
    void sameDimensionRefusesToWalkInsteadOfTravelling() {
        Decision decision = PortalRoutePolicy.decide(facts(OVERWORLD, OVERWORLD, PortalLink.LINKED, PortalLink.LINKED));
        assertEquals(new Decision.Refuse(Reason.SAME_DIMENSION), decision);
        assertEquals(
                new Decision.Refuse(Reason.SAME_DIMENSION),
                PortalRoutePolicy.decide(facts(NETHER, NETHER, PortalLink.LINKED, PortalLink.MISSING)));
    }

    @Test
    void unsupportedPairRefuses() {
        Decision decision = PortalRoutePolicy.decide(facts(NETHER, END, PortalLink.LINKED, PortalLink.LINKED));
        assertEquals(new Decision.Refuse(Reason.UNSUPPORTED_PAIR), decision);
        assertEquals(
                new Decision.Refuse(Reason.UNSUPPORTED_PAIR),
                PortalRoutePolicy.decide(facts(END, NETHER, PortalLink.LINKED, PortalLink.LINKED)));
    }

    @Test
    void unknownDimensionRefuses() {
        assertEquals(
                new Decision.Refuse(Reason.UNKNOWN_DIMENSION),
                PortalRoutePolicy.decide(
                        facts("minecraft:overworld", "mymod:pocket", PortalLink.LINKED, PortalLink.LINKED)));
        assertEquals(
                new Decision.Refuse(Reason.UNKNOWN_DIMENSION),
                PortalRoutePolicy.decide(facts("", NETHER, PortalLink.LINKED, PortalLink.LINKED)));
    }

    @Test
    void missingSourcePortalBuildsAndLights() {
        Decision decision = PortalRoutePolicy.decide(facts(OVERWORLD, NETHER, PortalLink.MISSING, PortalLink.LINKED));
        assertEquals(new Decision.BuildAndLight(), decision);
    }

    @Test
    void missingDestinationPortalBuildsAndLights() {
        Decision decision = PortalRoutePolicy.decide(facts(OVERWORLD, NETHER, PortalLink.LINKED, PortalLink.MISSING));
        assertEquals(new Decision.BuildAndLight(), decision);
    }

    @Test
    void bothPortalsMissingBuildsAndLights() {
        Decision decision = PortalRoutePolicy.decide(facts(OVERWORLD, END, PortalLink.MISSING, PortalLink.MISSING));
        assertEquals(new Decision.BuildAndLight(), decision);
    }

    @Test
    void unsafeLandingRefuses() {
        RouteFacts unsafe = new RouteFacts(OVERWORLD, NETHER, PortalLink.LINKED, PortalLink.LINKED, false, true, true);
        assertEquals(new Decision.Refuse(Reason.UNSAFE_LANDING), PortalRoutePolicy.decide(unsafe));
    }

    @Test
    void unsafeLandingRefusesBeforeBuilding() {
        RouteFacts unsafe =
                new RouteFacts(OVERWORLD, NETHER, PortalLink.MISSING, PortalLink.MISSING, false, true, true);
        assertEquals(new Decision.Refuse(Reason.UNSAFE_LANDING), PortalRoutePolicy.decide(unsafe));
    }

    @Test
    void missingConsentRefuses() {
        RouteFacts noConsent =
                new RouteFacts(OVERWORLD, NETHER, PortalLink.LINKED, PortalLink.LINKED, true, false, true);
        assertEquals(new Decision.Refuse(Reason.NO_OWNER_CONSENT), PortalRoutePolicy.decide(noConsent));
    }

    @Test
    void disabledExpeditionRefuses() {
        RouteFacts noExpedition =
                new RouteFacts(OVERWORLD, NETHER, PortalLink.LINKED, PortalLink.LINKED, true, true, false);
        assertEquals(new Decision.Refuse(Reason.EXPEDITION_DISABLED), PortalRoutePolicy.decide(noExpedition));
    }

    @Test
    void dimensionsNormalizeBeforeRouting() {
        Decision decision =
                PortalRoutePolicy.decide(facts(" Overworld ", "the_nether", PortalLink.LINKED, PortalLink.LINKED));
        assertEquals(new Decision.Travel(RouteKind.NETHER), decision);
    }

    @Test
    void routeKindIsEmptyForPairsThePolicyDoesNotRoute() {
        assertEquals(Optional.of(RouteKind.NETHER), PortalRoutePolicy.routeKind(OVERWORLD, NETHER));
        assertEquals(Optional.of(RouteKind.END), PortalRoutePolicy.routeKind(END, OVERWORLD));
        assertTrue(PortalRoutePolicy.routeKind(OVERWORLD, OVERWORLD).isEmpty());
        assertTrue(PortalRoutePolicy.routeKind(NETHER, END).isEmpty());
        assertTrue(PortalRoutePolicy.routeKind("", OVERWORLD).isEmpty());
        assertTrue(PortalRoutePolicy.routeKind("mymod:pocket", OVERWORLD).isEmpty());
    }

    @Test
    void malformedFactsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteFacts(OVERWORLD, NETHER, null, PortalLink.LINKED, true, true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RouteFacts(OVERWORLD, NETHER, PortalLink.LINKED, null, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> PortalRoutePolicy.decide(null));
        assertThrows(IllegalArgumentException.class, () -> new Decision.Travel(null));
        assertThrows(IllegalArgumentException.class, () -> new Decision.Refuse(null));
    }
}
