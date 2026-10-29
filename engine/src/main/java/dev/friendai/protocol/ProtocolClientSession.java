package dev.friendai.protocol;

import java.util.Objects;
import java.util.Optional;

public final class ProtocolClientSession {
    public enum State {
        AWAITING_HELLO,
        ACCEPTED,
        REJECTED
    }

    private final ProtocolCapabilities supported;
    private final boolean canSendAccept;
    private State state = State.AWAITING_HELLO;
    private ProtocolCapabilities negotiated;

    public ProtocolClientSession(ProtocolCapabilities supported, boolean canSendAccept) {
        this.supported = Objects.requireNonNull(supported, "supported");
        this.canSendAccept = canSendAccept;
        if (!canSendAccept) state = State.REJECTED;
    }

    public State state() {
        return state;
    }

    public Optional<ProtocolAccept> receiveHello(ProtocolHello hello) {
        if (state != State.AWAITING_HELLO || hello == null || hello.version() != ProtocolVersion.CURRENT) {
            state = State.REJECTED;
            return Optional.empty();
        }
        if (!canSendAccept) {
            state = State.REJECTED;
            return Optional.empty();
        }
        negotiated = supported.negotiatedWith(hello.capabilities());
        state = State.ACCEPTED;
        return Optional.of(new ProtocolAccept(ProtocolVersion.CURRENT, supported));
    }

    public boolean allowsFamily(ProtocolFamily family) {
        return state == State.ACCEPTED && negotiated.supportsFamily(family);
    }

    public boolean allowsAppearance() {
        return state == State.ACCEPTED && negotiated.supports("friendai:appearance");
    }

    public State reject() {
        if (state == State.AWAITING_HELLO) state = State.REJECTED;
        return state;
    }
}
