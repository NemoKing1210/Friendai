package dev.friendai.agent.coordination;

import java.util.UUID;

/**
 * One bounded request for a named piece of help from another owner's companion. It carries no access level, spend or
 * trade authority: accepting it authorizes only the named help, and it expires by itself.
 */
public record HelpRequest(UUID fromOwner, UUID toOwner, String help, long expiresAtTick) {
    public static final int MAX_HELP = 200;
    public static final long MAX_TTL_TICKS = 24_000;

    public HelpRequest {
        if (fromOwner == null || toOwner == null) throw new IllegalArgumentException("owner");
        if (fromOwner.equals(toOwner)) throw new IllegalArgumentException("a request needs two owners");
        help = help == null ? "" : help.strip();
        if (help.isEmpty() || help.length() > MAX_HELP) throw new IllegalArgumentException("help");
        if (expiresAtTick < 1) throw new IllegalArgumentException("expiresAtTick");
    }

    public boolean expired(long now) {
        return now >= expiresAtTick;
    }
}
