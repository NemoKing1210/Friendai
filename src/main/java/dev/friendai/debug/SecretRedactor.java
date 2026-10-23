package dev.friendai.debug;

import java.util.Collection;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class SecretRedactor {
    private SecretRedactor() {}

    /** Replaces every occurrence of each non-blank secret with {@code ***}. Secrets are read on each call. */
    public static UnaryOperator<String> of(Supplier<Collection<String>> secrets) {
        return text -> {
            String result = text;
            for (String secret : secrets.get()) {
                if (secret != null && !secret.isBlank()) {
                    result = result.replace(secret, "***");
                }
            }
            return result;
        };
    }
}
