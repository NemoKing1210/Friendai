package dev.friendai.protocol;

public final class ProtocolDataException extends IllegalArgumentException {
    private final DecodeResult.Reason reason;

    public ProtocolDataException(DecodeResult.Reason reason) {
        super("invalid protocol body");
        this.reason = reason;
    }

    public DecodeResult.Reason reason() {
        return reason;
    }
}
