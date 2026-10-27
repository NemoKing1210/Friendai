package dev.friendai.queue;

import com.google.gson.JsonObject;
import java.util.UUID;

/** Server-managed, serialized capture of an ephemeral spending scope for deferred queue work. */
public final class QueueTradeScope {
    public static final String ARG = "_trade_scope";

    public record EnqueueCapture(JsonObject args, int maxAttempts, UUID tradingScope) {
        public EnqueueCapture {
            args = copy(args);
            if (maxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
        }

        @Override
        public JsonObject args() {
            return copy(args);
        }
    }

    private QueueTradeScope() {}

    /** Adds the turn's scope to a new queued job; a model-provided marker is never accepted. */
    public static JsonObject capture(JsonObject source, UUID currentScope, boolean spending) {
        JsonObject args = copy(source);
        if (args.has(ARG)) throw new IllegalArgumentException(ARG + " is server-managed");
        if (spending) {
            if (currentScope == null) throw new IllegalArgumentException("spending jobs require an owner-initiated turn");
            args.addProperty(ARG, currentScope.toString());
        }
        return args;
    }

    /** Captures queue arguments and the matching caller scope; spending work is limited to one attempt. */
    public static EnqueueCapture captureEnqueue(JsonObject source, UUID currentScope, boolean spending,
                                                int requestedMaxAttempts) {
        if (requestedMaxAttempts < 1) throw new IllegalArgumentException("max attempts must be positive");
        JsonObject args = capture(source, currentScope, spending);
        return new EnqueueCapture(args, spending ? 1 : requestedMaxAttempts, read(args));
    }

    /** Preserves the original scope for spending edits; ordinary replacements discard obsolete scope markers. */
    public static JsonObject preserve(JsonObject replacement, JsonObject original, boolean spending) {
        JsonObject args = copy(replacement);
        if (args.has(ARG)) throw new IllegalArgumentException(ARG + " is server-managed");
        UUID captured = read(original);
        if (spending && captured == null) {
            throw new IllegalArgumentException("spending jobs cannot be edited without their original owner scope");
        }
        if (spending) args.addProperty(ARG, captured.toString());
        return args;
    }

    /** Returns the saved scope id, or null for an ordinary job. Malformed markers fail closed. */
    public static UUID read(JsonObject args) {
        if (args == null || !args.has(ARG)) return null;
        try {
            if (!args.get(ARG).isJsonPrimitive() || !args.getAsJsonPrimitive(ARG).isString()) {
                throw new IllegalArgumentException("queued trading scope is malformed");
            }
            return UUID.fromString(args.get(ARG).getAsString());
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("queued trading scope is malformed", malformed);
        }
    }

    /** Whether a saved job or plan step requires an owner scope from the current server lifetime. */
    public static boolean requiresFreshAuthority(JsonObject args, boolean spendingTool) {
        return spendingTool || (args != null && args.has(ARG));
    }

    /** A manual retry may be requested only by the owner and always references the saved scope. */
    public static UUID retry(JsonObject savedArgs, boolean spending, boolean ownerAccess) {
        if (spending && !ownerAccess) throw new IllegalArgumentException("only the owner may retry spending work");
        UUID captured = read(savedArgs);
        if (spending && captured == null) throw new IllegalArgumentException("spending job has no original owner scope");
        return captured;
    }

    private static JsonObject copy(JsonObject source) {
        return source == null ? new JsonObject() : source.deepCopy();
    }
}
