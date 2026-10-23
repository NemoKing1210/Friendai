package dev.friendai.task.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.build.BlueprintParser;
import dev.friendai.build.Generators;
import dev.friendai.build.ParseResult;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** Turns the tool arguments of a build request into a blueprint: exactly one of blueprint, generator, saved, structure. */
public final class BlueprintResolver {
    private static final String SOURCE_ERROR = "give exactly one of blueprint, generator, saved, structure";

    private BlueprintResolver() {}

    public static ParseResult resolve(JsonObject args, ServerLevel level, UUID author) {
        boolean blueprint = present(args, "blueprint");
        boolean generator = present(args, "generator");
        boolean saved = present(args, "saved");
        boolean structure = present(args, "structure");
        int sources = (blueprint ? 1 : 0) + (generator ? 1 : 0) + (saved ? 1 : 0) + (structure ? 1 : 0);
        if (sources != 1) {
            return ParseResult.failed(List.of(SOURCE_ERROR));
        }
        if (structure) {
            return TemplateConverter.convert(args.get("structure").getAsString(), level);
        }
        if (blueprint) {
            return BlueprintParser.parse(args.get("blueprint").getAsString(), knownState());
        }
        if (generator) {
            JsonElement spec;
            try {
                spec = JsonParser.parseString(args.get("generator").getAsString());
            } catch (RuntimeException e) {
                return ParseResult.failed(List.of("generator must be a JSON object"));
            }
            if (!spec.isJsonObject()) {
                return ParseResult.failed(List.of("generator must be a JSON object"));
            }
            return Generators.generate(spec.getAsJsonObject(), knownState());
        }
        String name = args.get("saved").getAsString();
        return BlueprintStore.forLevel(level)
                .load(name)
                .map(ParseResult::ok)
                .orElseGet(() -> ParseResult.failed(List.of("no blueprint named " + name)));
    }

    /** Whether a block state string exists in this game version. */
    public static Predicate<String> knownState() {
        return BlueprintResolver::isKnownState;
    }

    private static boolean isKnownState(String state) {
        try {
            BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, state, false);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean present(JsonObject args, String name) {
        return args.has(name)
                && !args.get(name).isJsonNull()
                && args.get(name).isJsonPrimitive()
                && !args.get(name).getAsString().isBlank();
    }
}
