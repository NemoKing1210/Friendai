package dev.friendai.agent.autonomy;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Fixed step lists for routine needs, so the companion handles them without asking the model. */
public final class ReflexPlans {
    private ReflexPlans() {}

    /** The template for a reflex need, or empty when the need has none or nowhere to go. */
    public static Optional<List<ReflexStep>> plan(Need need, AutonomyFacts f, AutonomySettings s) {
        return switch (need.kind()) {
            case UNLOAD -> f.storageHere().or(f::homeHere)
                    .map(place -> List.of(step("go_to_place", "name", place.name()),
                            step("deposit_items", "place", place.name())));
            case SHELTER, SURVIVE -> Optional.of(f.homeHere()
                    .map(home -> List.of(step("go_to_place", "name", home.name()), step("stay")))
                    .orElseGet(() -> f.ownerOnline()
                            ? List.of(step("follow_player"))
                            : List.of(step("light_area"), step("stay"))));
            case COLLECT -> f.homeHere()
                    .map(home -> List.of(step("go_to_place", "name", home.name()), step("collect_smelted")));
            default -> Optional.empty();
        };
    }

    /** What to do without the model when the budget is spent, or empty when the need has no template. */
    public static Optional<List<ReflexStep>> fallback(Need need, AutonomyFacts f) {
        return switch (need.kind()) {
            case STOCK -> stockSteps(f);
            case PROCESS -> Optional.of(List.of(step("smelt_items")));
            case FARM -> f.farms().stream()
                    .filter(FarmRef::sameDimension)
                    .max(Comparator.comparingLong(FarmRef::millisSinceVisit))
                    .map(farm -> List.of(step("tend_farm", "place", farm.name())));
            default -> Optional.empty();
        };
    }

    /** Gets the item the stage is short of, one step per kind, so a spent budget still moves the ladder. */
    private static Optional<List<ReflexStep>> stockSteps(AutonomyFacts f) {
        List<ReflexStep> steps = new ArrayList<>();
        ProgressionLadder.missingStock(f).forEach((kind, missing) -> {
            if (kind == StockKind.LOGS) {
                steps.add(step("fell_tree"));
                return;
            }
            kind.itemId().ifPresent(id -> {
                JsonObject args = new JsonObject();
                args.addProperty("item", id);
                args.addProperty("count", Math.max(1, Math.min(64, missing)));
                steps.add(new ReflexStep("obtain_item", args));
            });
        });
        return steps.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(steps));
    }

    private static ReflexStep step(String tool) {
        return new ReflexStep(tool, new JsonObject());
    }

    private static ReflexStep step(String tool, String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return new ReflexStep(tool, args);
    }
}
