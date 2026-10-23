package dev.friendai.build;

import java.util.List;
import java.util.Optional;

/** Either a parsed blueprint or the complete list of problems found while parsing it. */
public record ParseResult(Optional<Blueprint> blueprint, List<String> errors) {
    public ParseResult {
        errors = List.copyOf(errors);
    }

    public static ParseResult ok(Blueprint blueprint) {
        return new ParseResult(Optional.of(blueprint), List.of());
    }

    public static ParseResult failed(List<String> errors) {
        return new ParseResult(Optional.empty(), errors);
    }
}
