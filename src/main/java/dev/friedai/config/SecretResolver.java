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

    /**
     * The form of a key shown in the GUI: the key itself when {@code reveal} is set, otherwise a fixed
     * placeholder that leaks neither content nor length. Environment references are not secrets.
     */
    public static String display(String secret, boolean reveal) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (reveal || secret.startsWith(ENV_PREFIX)) {
            return secret;
        }
        return "••••••••";
    }
}
