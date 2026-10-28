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
            case HUNGER -> Optional.of(f.foodPoints() > 0
                    ? List.of(step("eat"))
                    : List.of(step("get_food")));
            case SLEEP -> f.homeHere()
                    .map(home -> List.of(step("go_to_place", "name", home.name()), step("sleep")));
            case FARM_CREATE -> f.homeHere().map(home -> List.of(step("go_to_place", "name", home.name()),
                    farmStep()));
            case UNLOAD -> f.storageHere().or(f::homeHere)
                    .map(place -> List.of(step("go_to_place", "name", place.name()),
                            step("deposit_items", "place", place.name())));
            case SHELTER -> Optional.of(f.homeHere()
                    .map(home -> List.of(step("go_to_place", "name", home.name()), step("stay")))
                    .orElseGet(() -> List.of(step("light_area"), step("stay"))));
            case SURVIVE -> Optional.of(survivePlan(f));
            case COLLECT -> f.homeHere()
                    .map(home -> List.of(step("go_to_place", "name", home.name()), step("collect_smelted")));
            default -> Optional.empty();
        };
    }

    /** What to do without the model when the budget is spent, or empty when the need has no template. */
    public static Optional<List<ReflexStep>> fallback(Need need, AutonomyFacts f) {
        return switch (need.kind()) {
            case STOCK -> stockSteps(f);
            case GEAR -> gearSteps(f);
            case PROCESS -> Optional.of(List.of(step("smelt_items")));
            case FOOD -> Optional.of(List.of(step("get_food")));
            case LIGHT -> Optional.of(List.of(step("obtain_item", "item", "minecraft:torch", "count", 16)));
            case BUILD -> Optional.of(List.of(buildShelterStep()));
            case STORAGE -> f.homeHere().map(home -> List.of(
                    step("go_to_place", "name", home.name()),
                    step("obtain_item", "item", "minecraft:chest", "count", 1),
                    step("place_block", "block", "minecraft:chest")));
            case FARM -> f.farms().stream()
                    .filter(FarmRef::sameDimension)
                    .max(Comparator.comparingLong(FarmRef::millisSinceVisit))
                    .map(farm -> List.of(step("tend_farm", "place", farm.name())));
            default -> Optional.empty();
        };
    }

    /** The template for a need kind, for callers that only have the stored shift's kind. */
    public static Optional<List<ReflexStep>> fallback(NeedKind kind, AutonomyFacts f) {
        return fallback(new Need(kind, 50, Executor.DIRECTOR, ""), f);
    }

    private static List<ReflexStep> survivePlan(AutonomyFacts f) {
        List<ReflexStep> steps = new ArrayList<>();
        f.homeHere().ifPresentOrElse(home -> steps.add(step("go_to_place", "name", home.name())),
                () -> steps.add(step("light_area")));
        if (f.foodPoints() > 0) {
            steps.add(step("eat"));
        }
        steps.add(step("stay"));
        return steps;
    }

    private static ReflexStep farmStep() {
        JsonObject args = new JsonObject();
        args.addProperty("radius", 4);
        args.addProperty("till", true);
        args.addProperty("crop", "minecraft:wheat_seeds");
        return new ReflexStep("farm", args);
    }

    /** A small wooden house; the finished build is remembered as a home by {@code BuildTask}. */
    private static ReflexStep buildShelterStep() {
        JsonObject generator = new JsonObject();
        generator.addProperty("type", "house");
        generator.addProperty("width", 5);
        generator.addProperty("depth", 5);
        generator.addProperty("walls", "minecraft:oak_planks");
        generator.addProperty("floor", "minecraft:oak_planks");
        generator.addProperty("roof", "minecraft:oak_planks");
        generator.addProperty("name", "shelter");
        JsonObject args = new JsonObject();
        args.addProperty("generator", generator.toString());
        return new ReflexStep("build_structure", args);
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

    /** Asks for the missing tool, armor or station, so a spent budget still closes the ladder gap. */
    private static Optional<List<ReflexStep>> gearSteps(AutonomyFacts f) {
        List<ReflexStep> steps = new ArrayList<>();
        for (String item : ProgressionLadder.missingGearItemIds(f)) {
            steps.add(step("obtain_item", "item", item, "count", 1));
        }
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

    private static ReflexStep step(String tool, String key, String value, String numberKey, int number) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        args.addProperty(numberKey, number);
        return new ReflexStep(tool, args);
    }
}
