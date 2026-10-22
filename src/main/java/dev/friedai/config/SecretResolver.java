package dev.friedai.config;

import java.util.function.Function;

public final class SecretResolver {
    private static final String ENV_PREFIX = "env:";

    private SecretResolver() {}

    /** "env:NAME" is read from the environment ("" if unset); anything else is returned as is. */
    public static String resolve(String value, Function<String, String> env) {
        if (value == null) {
            return "";
        }
        if (value.startsWith(ENV_PREFIX)) {
            String v = env.apply(value.substring(ENV_PREFIX.length()).strip());
            return v == null ? "" : v;
        }
        return value;
    }

    /** A display form that is safe to show in the GUI. Environment references are not secrets. */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.startsWith(ENV_PREFIX)) {
            return secret;
        }
        if (secret.length() < 10) {
            return "•••";
        }
        return secret.substring(0, 3) + "…" + secret.substring(secret.length() - 3);
    }
}
