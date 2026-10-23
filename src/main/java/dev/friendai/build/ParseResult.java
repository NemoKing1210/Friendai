package dev.friendai.build;

import java.util.List;
import java.util.Optional;

/**
 * Either a parsed blueprint or the complete list of problems found while parsing it. Notes describe what the
 * parser changed on the way (for example skipped liquid blocks); they never prevent a build.
 */
public record ParseResult(Optional<Blueprint> blueprint, List<String> errors, List<String> notes) {
    public ParseResult {
        errors = List.copyOf(errors);
        notes = List.copyOf(notes);
    }

    public ParseResult(Optional<Blueprint> blueprint, List<String> errors) {
        this(blueprint, errors, List.of());
    }

    public static ParseResult ok(Blueprint blueprint) {
        return new ParseResult(Optional.of(blueprint), List.of(), List.of());
    }

    public static ParseResult ok(Blueprint blueprint, List<String> notes) {
        return new ParseResult(Optional.of(blueprint), List.of(), notes);
    }

    public static ParseResult failed(List<String> errors) {
        return new ParseResult(Optional.empty(), errors, List.of());
    }
}
