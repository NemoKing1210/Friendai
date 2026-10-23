package dev.friendai.llm.provider;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Maps model names to a protocol.
 *
 * @param modelGlob glob with '*' only, case-insensitive
 * @param baseUrlOverride base URL for matching models, nullable
 */
public record RoutingRule(String modelGlob, String protocol, String baseUrlOverride) {
    public boolean matches(String model) {
        String regex = Arrays.stream(modelGlob.split("\\*", -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".*"));
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
                .matcher(model)
                .matches();
    }
}
