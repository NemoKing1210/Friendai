package dev.friendai.protocol;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

public enum ProtocolFamily {
    CONTROL(1, 8_192, 1 << 20, Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13), Set.of(128)),
    MEMORY(2, 4_096, 1 << 20, Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9), Set.of(128)),
    PLACES(3, 1_024, 1 << 20, Set.of(1, 2, 3, 4), Set.of(128)),
    INSTRUCTIONS(4, 65_536, 65_536, Set.of(1, 2, 3, 4, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26), Set.of(128)),
    TRADING(5, 16_384, 65_536, Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), Set.of(128)),
    DEBUG(6, 1_024, 65_536, Set.of(1, 2), Set.of(128, 129)),
    SPEECH(7, 2_048, 16_384, Set.of(1), Set.of(128));

    private final int id;
    private final int clientToServerMaxFrameBytes;
    private final int serverToClientMaxFrameBytes;
    private final Set<Integer> clientToServerOperations;
    private final Set<Integer> serverToClientOperations;

    ProtocolFamily(
            int id,
            int clientToServerMaxFrameBytes,
            int serverToClientMaxFrameBytes,
            Set<Integer> clientToServerOperations,
            Set<Integer> serverToClientOperations) {
        this.id = id;
        this.clientToServerMaxFrameBytes = clientToServerMaxFrameBytes;
        this.serverToClientMaxFrameBytes = serverToClientMaxFrameBytes;
        this.clientToServerOperations = clientToServerOperations;
        this.serverToClientOperations = serverToClientOperations;
    }

    public int id() {
        return id;
    }

    public int maxFrameBytes(ProtocolDirection direction) {
        return direction == ProtocolDirection.CLIENT_TO_SERVER
                ? clientToServerMaxFrameBytes
                : serverToClientMaxFrameBytes;
    }

    public boolean supports(ProtocolDirection direction, int operationId) {
        return (direction == ProtocolDirection.CLIENT_TO_SERVER ? clientToServerOperations : serverToClientOperations)
                .contains(operationId);
    }

    public static Optional<ProtocolFamily> fromId(int id) {
        return Arrays.stream(values()).filter(family -> family.id == id).findFirst();
    }
}
