package dev.friendai.protocol;

import java.util.Objects;
import java.util.Optional;

/** Applies handshake, capability, and monotonic request checks before a decoded request can reach an adapter. */
public final class ProtocolServerRequestGate {
    public enum Rejection {
        SESSION_NOT_ACCEPTED,
        FAMILY_NOT_NEGOTIATED,
        STALE_REQUEST_ID,
        MALFORMED,
        UNSUPPORTED
    }

    public sealed interface Admission permits Accepted, Rejected {}

    public record Accepted(DecodedClientRequest request) implements Admission {
        public Accepted {
            Objects.requireNonNull(request, "request");
        }
    }

    public record Rejected(Rejection reason, DecodeResult.Reason decodeReason) implements Admission {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private final ProtocolServerSession session;
    private final ProtocolRequestSequence sequence = new ProtocolRequestSequence();

    public ProtocolServerRequestGate(
            long joinedAtTick, ProtocolCapabilities supported, boolean canSendHello, boolean canReceiveAccept) {
        session = ProtocolServerSession.start(joinedAtTick, supported, canSendHello, canReceiveAccept);
    }

    public ProtocolServerSession.State state() {
        return session.state();
    }

    public Optional<ProtocolHello> hello() {
        return session.hello();
    }

    public ProtocolServerSession.State accept(ProtocolAccept accept, long serverTick) {
        return session.accept(accept, serverTick);
    }

    public ProtocolServerSession.State tick(long serverTick) {
        return session.tick(serverTick);
    }

    public boolean allowsFamily(ProtocolFamily family) {
        return session.allowsFamily(family);
    }

    public boolean allowsAppearance() {
        return session.allowsAppearance();
    }

    public ProtocolServerSession.State disconnect() {
        return session.disconnect();
    }

    /** Decodes only after acceptance, then consumes the request ID only for an authorized family. */
    public Admission admit(byte[] encoded) {
        if (session.state() != ProtocolServerSession.State.ACCEPTED) {
            return new Rejected(Rejection.SESSION_NOT_ACCEPTED, null);
        }

        DecodeResult<DecodedClientRequest> decoded = ClientRequestCodec.decode(encoded);
        if (decoded instanceof DecodeResult.Malformed<DecodedClientRequest> malformed) {
            return new Rejected(Rejection.MALFORMED, malformed.reason());
        }
        if (decoded instanceof DecodeResult.Unsupported<DecodedClientRequest> unsupported) {
            return new Rejected(Rejection.UNSUPPORTED, unsupported.reason());
        }

        DecodedClientRequest request = ((DecodeResult.Valid<DecodedClientRequest>) decoded).value();
        if (!session.allowsFamily(request.request().family())) {
            return new Rejected(Rejection.FAMILY_NOT_NEGOTIATED, null);
        }
        if (!sequence.acceptIncoming(request.requestId())) {
            return new Rejected(Rejection.STALE_REQUEST_ID, DecodeResult.Reason.INVALID_REQUEST_ID);
        }
        return new Accepted(request);
    }
}
