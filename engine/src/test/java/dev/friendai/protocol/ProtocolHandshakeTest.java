package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ProtocolHandshakeTest {
    @Test
    void negotiatesOnlyKnownCapabilitiesSupportedByBothPeers() {
        ProtocolCapabilities server = ProtocolCapabilities.of("friendai:control", "friendai:memory", "future:extra");
        ProtocolCapabilities client = ProtocolCapabilities.of("friendai:control", "future:extra", "client:unknown");

        ProtocolCapabilities negotiated = server.negotiatedWith(client);

        assertEquals(List.of("friendai:control"), negotiated.ids());
        assertTrue(negotiated.supportsFamily(ProtocolFamily.CONTROL));
        assertFalse(negotiated.supportsFamily(ProtocolFamily.MEMORY));
        assertFalse(negotiated.supports("future:extra"));
        ProtocolCapabilities reordered = ProtocolCapabilities.of("friendai:memory", "friendai:control");
        assertEquals(ProtocolCapabilities.of("friendai:control", "friendai:memory"), reordered);
        assertEquals(
                ProtocolCapabilities.of("friendai:control", "friendai:memory").hashCode(), reordered.hashCode());
    }

    @Test
    void rejectsInvalidCapabilityNamesDuplicatesAndOversizedLists() {
        assertThrows(
                IllegalArgumentException.class, () -> ProtocolCapabilities.of("friendai:control", "friendai:control"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolCapabilities.of("not-namespaced"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolCapabilities.of("friendai:" + "a".repeat(56)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolCapabilities.of(
                        "x:a", "x:b", "x:c", "x:d", "x:e", "x:f", "x:g", "x:h", "x:i", "x:j", "x:k", "x:l", "x:m",
                        "x:n", "x:o", "x:p", "x:q", "x:r", "x:s", "x:t", "x:u", "x:v", "x:w", "x:x", "x:y", "x:z",
                        "x:1", "x:2", "x:3", "x:4", "x:5", "x:6", "x:7"));
    }

    @Test
    void encodesHelloWithBoundedUtf8CapabilityIds() {
        byte[] encoded =
                ProtocolBootstrapCodec.encodeHello(new ProtocolHello(1, ProtocolCapabilities.of("friendai:control")));

        assertArrayEquals(
                new byte[] {1, 1, 16, 'f', 'r', 'i', 'e', 'n', 'd', 'a', 'i', ':', 'c', 'o', 'n', 't', 'r', 'o', 'l'},
                encoded);

        DecodeResult<?> result = ProtocolBootstrapCodec.decodeHello(encoded);
        assertInstanceOf(DecodeResult.Valid.class, result);
        ProtocolHello decoded = assertInstanceOf(ProtocolHello.class, ((DecodeResult.Valid<?>) result).value());
        assertEquals(1, decoded.version());
        assertEquals(List.of("friendai:control"), decoded.capabilities().ids());
    }

    @Test
    void rejectsMalformedUtf8AndUnsupportedBootstrapVersions() {
        byte[] malformed = {1, 1, 1, (byte) 0xff};
        byte[] unsupportedVersion = {2, 0};

        assertInstanceOf(DecodeResult.Malformed.class, ProtocolBootstrapCodec.decodeHello(malformed));
        assertInstanceOf(DecodeResult.Unsupported.class, ProtocolBootstrapCodec.decodeHello(unsupportedVersion));
    }

    @Test
    void serverRequiresMatchingAcceptanceBeforeFamilyDispatchAndTimesOutAtOneHundredTicks() {
        ProtocolServerSession session = ProtocolServerSession.start(
                10, ProtocolCapabilities.of("friendai:control", "friendai:memory"), true, true);

        assertEquals(ProtocolServerSession.State.PENDING, session.state());
        assertFalse(session.allowsFamily(ProtocolFamily.CONTROL));
        assertEquals(ProtocolServerSession.State.PENDING, session.tick(109));
        assertEquals(
                ProtocolServerSession.State.ACCEPTED,
                session.accept(
                        new ProtocolAccept(1, ProtocolCapabilities.of("friendai:control", "future:extra")), 109));
        assertTrue(session.allowsFamily(ProtocolFamily.CONTROL));
        assertFalse(session.allowsFamily(ProtocolFamily.MEMORY));

        ProtocolServerSession expired =
                ProtocolServerSession.start(10, ProtocolCapabilities.of("friendai:control"), true, true);
        assertEquals(ProtocolServerSession.State.TIMED_OUT, expired.tick(110));
        assertFalse(expired.allowsFamily(ProtocolFamily.CONTROL));
    }

    @Test
    void rejectsMissingChannelsAndMismatchedAcceptance() {
        ProtocolServerSession missingAcceptChannel =
                ProtocolServerSession.start(0, ProtocolCapabilities.of("friendai:control"), true, false);
        ProtocolServerSession missingHelloChannel =
                ProtocolServerSession.start(0, ProtocolCapabilities.of("friendai:control"), false, true);
        ProtocolServerSession mismatched =
                ProtocolServerSession.start(0, ProtocolCapabilities.of("friendai:control"), true, true);

        assertEquals(ProtocolServerSession.State.REJECTED, missingAcceptChannel.state());
        assertEquals(ProtocolServerSession.State.REJECTED, missingHelloChannel.state());
        assertEquals(
                ProtocolServerSession.State.REJECTED,
                mismatched.accept(new ProtocolAccept(2, ProtocolCapabilities.of("friendai:control")), 0));
    }

    @Test
    void clientWaitsForValidHelloAndAcceptChannel() {
        ProtocolClientSession client = new ProtocolClientSession(ProtocolCapabilities.of("friendai:control"), true);
        ProtocolClientSession missingAcceptChannel =
                new ProtocolClientSession(ProtocolCapabilities.of("friendai:control"), false);

        ProtocolAccept accept = client.receiveHello(
                        new ProtocolHello(1, ProtocolCapabilities.of("friendai:control", "friendai:memory")))
                .orElseThrow();

        assertEquals(new ProtocolAccept(1, ProtocolCapabilities.of("friendai:control")), accept);
        assertEquals(ProtocolClientSession.State.ACCEPTED, client.state());
        assertTrue(client.allowsFamily(ProtocolFamily.CONTROL));
        assertFalse(client.allowsFamily(ProtocolFamily.MEMORY));
        assertTrue(missingAcceptChannel
                .receiveHello(new ProtocolHello(1, ProtocolCapabilities.of()))
                .isEmpty());
        assertEquals(ProtocolClientSession.State.REJECTED, missingAcceptChannel.state());
    }
}
