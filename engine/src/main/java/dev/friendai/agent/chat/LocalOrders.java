package dev.friendai.agent.chat;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.ToolCall;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A deliberately small grammar for the orders that must work without a model. An exact phrase becomes the same tool
 * call the model would make; everything else returns empty and still goes to the brain.
 */
public final class LocalOrders {
    private static final Set<String> FOLLOW = Set.of(
            "follow me",
            "follow",
            "follow us",
            "come along",
            "come with me",
            "иди за мной",
            "следуй за мной",
            "пойдем",
            "пойдём",
            "пошли",
            "иди со мной");
    private static final Set<String> STAY = Set.of(
            "stay",
            "stay here",
            "wait",
            "wait here",
            "оставайся",
            "оставайся здесь",
            "стой",
            "жди",
            "жди здесь",
            "подожди",
            "подожди здесь");
    private static final Set<String> COME =
            Set.of("come", "come here", "come to me", "подойди", "подойди ко мне", "иди сюда", "ко мне");
    private static final Set<String> STOP = Set.of(
            "stop",
            "stop it",
            "stop now",
            "stop it now",
            "halt",
            "стоп",
            "остановись",
            "хватит",
            "перестань",
            "прекрати");
    private static final Set<String> STATUS = Set.of("status", "статус");
    private static final Set<String> INVENTORY = Set.of("inventory", "show inventory", "инвентарь", "покажи инвентарь");

    private static final List<String> POLITE_PREFIXES = List.of(
            "please ",
            "can you ",
            "could you ",
            "would you ",
            "will you ",
            "пожалуйста ",
            "можешь ",
            "ты можешь ",
            "не мог бы ты ");

    private static final Set<String> QUESTION_WORDS = Set.of(
            "why", "how", "what", "when", "where", "who", "which", "did", "do ", "does ", "почему", "как", "что",
            "когда", "где", "кто");

    private LocalOrders() {}

    public static Optional<ToolCall> parse(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty() || QUESTION_WORDS.stream().anyMatch(normalized::startsWith)) {
            return Optional.empty();
        }
        if (FOLLOW.contains(normalized)) {
            return Optional.of(call("follow_player", new JsonObject()));
        }
        if (STAY.contains(normalized)) {
            return Optional.of(call("stay", new JsonObject()));
        }
        if (COME.contains(normalized)) {
            JsonObject args = new JsonObject();
            args.addProperty("target", "owner");
            return Optional.of(call("go_to", args));
        }
        if (STOP.contains(normalized)) {
            return Optional.of(call("stop", new JsonObject()));
        }
        if (STATUS.contains(normalized)) {
            return Optional.of(call("queue_show", new JsonObject()));
        }
        if (INVENTORY.contains(normalized)) {
            return Optional.of(call("get_inventory", new JsonObject()));
        }
        return Optional.empty();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String value = text.toLowerCase(Locale.ROOT)
                .replaceAll("[!?.,;:]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : POLITE_PREFIXES) {
                if (value.startsWith(prefix)) {
                    value = value.substring(prefix.length()).trim();
                    changed = true;
                }
            }
            if (value.endsWith(" please")) {
                value = value.substring(0, value.length() - " please".length()).trim();
                changed = true;
            }
            if (value.endsWith(" пожалуйста")) {
                value = value.substring(0, value.length() - " пожалуйста".length())
                        .trim();
                changed = true;
            }
        }
        return value;
    }

    private static ToolCall call(String name, JsonObject args) {
        return new ToolCall("local-" + name, name, args, args.toString(), null);
    }
}
