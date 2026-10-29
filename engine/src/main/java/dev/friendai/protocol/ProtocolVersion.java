package dev.friendai.protocol;

public final class ProtocolVersion {
    public static final int CURRENT = 1;
    public static final int HEADER_BYTES = 15;
    public static final int ABSOLUTE_MAX_FRAME_BYTES = 1 << 20;

    private ProtocolVersion() {}
}
