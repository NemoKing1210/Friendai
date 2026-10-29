package dev.friendai.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ProtocolCapabilities {
    public static final int MAX_COUNT = 32;
    public static final int MAX_ID_BYTES = 64;
    private static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Set<String> KNOWN = Set.of(
            "friendai:control",
            "friendai:memory",
            "friendai:places",
            "friendai:instructions",
            "friendai:trading",
            "friendai:debug",
            "friendai:speech",
            "friendai:appearance");

    private final List<String> ids;

    private ProtocolCapabilities(Collection<String> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.size() > MAX_COUNT) throw new IllegalArgumentException("too many protocol capabilities");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || !NAMESPACED_ID.matcher(id).matches())
                throw new IllegalArgumentException("invalid protocol capability id");
            int byteLength = id.getBytes(StandardCharsets.UTF_8).length;
            if (byteLength == 0 || byteLength > MAX_ID_BYTES)
                throw new IllegalArgumentException("protocol capability id is too long");
            if (!unique.add(id)) throw new IllegalArgumentException("duplicate protocol capability id");
        }
        this.ids = List.copyOf(unique);
    }

    public static ProtocolCapabilities of(String... ids) {
        return new ProtocolCapabilities(List.of(ids));
    }

    /** Capabilities implemented by the current Friendai protocol build, in stable wire-advertisement order. */
    public static ProtocolCapabilities friendai() {
        return of(
                "friendai:control",
                "friendai:memory",
                "friendai:places",
                "friendai:instructions",
                "friendai:trading",
                "friendai:debug",
                "friendai:speech",
                "friendai:appearance");
    }

    public List<String> ids() {
        return ids;
    }

    public boolean supports(String id) {
        return ids.contains(id);
    }

    public boolean supportsFamily(ProtocolFamily family) {
        String capability = switch (family.id()) {
            case 1 -> "friendai:control";
            case 2 -> "friendai:memory";
            case 3 -> "friendai:places";
            case 4 -> "friendai:instructions";
            case 5 -> "friendai:trading";
            case 6 -> "friendai:debug";
            case 7 -> "friendai:speech";
            default -> throw new IllegalArgumentException("unknown protocol family");
        };
        return supports(capability);
    }

    public ProtocolCapabilities negotiatedWith(ProtocolCapabilities peer) {
        Objects.requireNonNull(peer, "peer");
        return new ProtocolCapabilities(
                ids.stream().filter(KNOWN::contains).filter(peer.ids::contains).toList());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProtocolCapabilities that && Set.copyOf(ids).equals(Set.copyOf(that.ids));
    }

    @Override
    public int hashCode() {
        return Set.copyOf(ids).hashCode();
    }

    @Override
    public String toString() {
        return "ProtocolCapabilities" + ids;
    }
}
