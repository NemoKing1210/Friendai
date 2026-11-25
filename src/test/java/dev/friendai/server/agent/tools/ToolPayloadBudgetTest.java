package dev.friendai.server.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.prompt.DefaultSections;
import dev.friendai.agent.prompt.TurnContext;
import dev.friendai.agent.route.IntentVocabulary;
import dev.friendai.agent.route.RouteDecision;
import dev.friendai.agent.route.RouteSignals;
import dev.friendai.agent.route.TurnMode;
import dev.friendai.agent.route.TurnRouter;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.agent.turn.Persona;
import dev.friendai.agent.turn.TurnKind;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.memory.MemoryActions;
import dev.friendai.features.memory.minecraft.tool.MemoryAccess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins what one request may carry. The router's groups decide the tool payload, and a route that quietly widens back
 * to the whole registry is invisible in a diff but expensive on every turn: these bounds fail instead.
 *
 * <p>The numbers are measured against the real {@link FriendaiTools} registry, not a fixture, so a new tool or a
 * widened route shows up here. Writes {@code build/tool-payload-report.md} beside the inventory.
 */
class ToolPayloadBudgetTest {
    /**
     * Tools an ordinary action turn may send. The floor is the work controls, looking, moving and remembering; a
     * matched kind of work adds its own group.
     */
    static final int ACTION_TOOLS = 52;
    /** Model-facing bytes (name + description + schema) an ordinary action turn may send. */
    static final int ACTION_BYTES = 47_000;
    /** The same for a multi-step order, which also loads the building and blueprint groups. */
    static final int GOAL_TOOLS = 65;

    static final int GOAL_BYTES = 47_500;
    /** A greeting or a question: reading, remembering and the inventory, never a whole action catalogue. */
    static final int CHAT_TOOLS = 50;

    static final int CHAT_BYTES = 36_000;
    /** News that needs no work, or an order the router does not recognize: the reading floor, nothing more. */
    static final int LIGHT_TURN_TOOLS = 50;

    static final int LIGHT_TURN_BYTES = 36_000;
    /** A repair keeps its whole kit, because the cause is unknown until the diagnosis is read. */
    static final int RECOVERY_TOOLS = 80;

    static final int RECOVERY_BYTES = 64_000;
    /** Prompt bytes the per-tool guidance adds to the system prompt on an ordinary action turn. */
    static final int ACTION_NOTES = 6_000;
    /** A conversational turn never carries this share of the whole registry's model-facing bytes. */
    static final double CHAT_SHARE = 0.6;

    /** One turn's input: the words, whose turn it is, and whether a plan is blocked. */
    private record Script(String text, TurnKind turn, boolean blocked) {}

    private static final List<Script> SCRIPTS = List.of(
            // Recognized orders, one kind of work and several.
            new Script("mine some iron", TurnKind.PLAYER, false),
            new Script("Добудь три булыжника.", TurnKind.PLAYER, false),
            new Script("craft a wooden pickaxe", TurnKind.PLAYER, false),
            new Script("bring me eight planks", TurnKind.PLAYER, false),
            new Script("store these items in the chest here", TurnKind.PLAYER, false),
            new Script("build a house and bring iron", TurnKind.PLAYER, false),
            new Script("Построй дом и принеси железо", TurnKind.PLAYER, false),
            // Conversation and questions.
            new Script("Hello!", TurnKind.PLAYER, false),
            new Script("Спасибо!", TurnKind.PLAYER, false),
            new Script("where is the nearest village?", TurnKind.PLAYER, false),
            new Script("what are you doing?", TurnKind.PLAYER, false),
            // Turns that used to send every group: news, an empty message, and an order in a language with no
            // intent file, which the router cannot recognize at all.
            new Script("", TurnKind.EVENT, false),
            new Script("the furnace finished", TurnKind.EVENT, false),
            new Script("", TurnKind.PLAYER, false),
            new Script("qwrtp zxcvb", TurnKind.PLAYER, false),
            new Script("plmk nbvc", TurnKind.PLAYER, false),
            // A failed plan keeps the repair kit.
            new Script("", TurnKind.PLAYER, true));

    private record Payload(Script script, RouteDecision decision, List<ToolSpec> tools, int bytes, int notes) {
        String label() {
            String text = script.text().isEmpty() ? "(no text)" : script.text();
            return text + " [" + script.turn() + (script.blocked() ? ", blocked" : "") + "]";
        }
    }

    /** Limits for one routed mode; the assertion names the mode so a failure says which contract broke. */
    private record Limit(TurnMode mode, int tools, int bytes, int notes) {}

    private static Payload payload(TurnRouter router, ToolCatalog catalog, List<ToolSpec> specs, Script script) {
        RouteDecision decision = router.route(new RouteSignals(
                script.turn(),
                script.text(),
                false,
                script.blocked(),
                true,
                false,
                false,
                false,
                false,
                false,
                0L,
                "",
                0L,
                List.of(),
                0L));
        List<ToolSpec> sent = catalog.filter(specs, decision.groups(), decision.mode() == TurnMode.CHAT);
        return new Payload(script, decision, sent, bytes(sent), notes(catalog, decision.groups()));
    }

    private static List<Payload> payloads(TurnRouter router, ToolCatalog catalog, List<ToolSpec> specs) {
        List<Payload> all = new ArrayList<>();
        for (Script script : SCRIPTS) {
            all.add(payload(router, catalog, specs, script));
        }
        return all;
    }

    /** The {@code Tool notes} block the same route renders into the system prompt. */
    private static int notes(ToolCatalog catalog, Set<ToolGroup> groups) {
        int total = 0;
        for (String line : catalog.hints(groups)) {
            total += line.getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    private static int bytes(List<ToolSpec> specs) {
        int total = 0;
        for (ToolSpec spec : specs) {
            total += (spec.name() + spec.description() + spec.parametersSchema())
                    .getBytes(StandardCharsets.UTF_8)
                    .length;
        }
        return total;
    }

    private static FriendaiTools.Builtins builtins() {
        return FriendaiTools.build(() -> 8, MEMORY_ENABLED);
    }

    /** Memory on, as on a real server; no memory tool is executed, so the actions are never reached. */
    private static final MemoryAccess MEMORY_ENABLED = new MemoryAccess() {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public MemoryActions actions(CompanionEntity companion, Caller caller) {
            throw new IllegalStateException("the payload is measured, not executed");
        }
    };

    @Test
    void everyRouteStaysWithinItsPayloadBudget() throws IOException {
        FriendaiTools.Builtins builtins = builtins();
        List<ToolSpec> specs = builtins.registry().specs(AccessLevel.OWNER);
        List<Payload> payloads = payloads(new TurnRouter(IntentVocabulary.load()), builtins.catalog(), specs);
        writeReport(payloads, specs, builtins.catalog());
        int full = bytes(specs);
        for (Payload p : payloads) {
            Limit limit = limitFor(p.decision().mode());
            assertTrue(
                    p.tools().size() <= limit.tools(),
                    p.label() + " (" + p.decision().mode() + ") sends "
                            + p.tools().size() + " tools, above " + limit.tools() + ": "
                            + p.decision().groups());
            assertTrue(
                    p.bytes() <= limit.bytes(),
                    p.label() + " (" + p.decision().mode() + ") sends " + p.bytes() + " bytes, above " + limit.bytes()
                            + ": " + p.decision().groups());
            if (limit.notes() > 0) {
                assertTrue(
                        p.notes() <= limit.notes(),
                        p.label() + " adds " + p.notes() + " bytes of tool notes, above " + limit.notes() + ": "
                                + p.decision().groups());
            }
            if (p.decision().mode() == TurnMode.CHAT) {
                assertTrue(
                        p.bytes() < full * CHAT_SHARE,
                        p.label() + " sends " + p.bytes() + " of " + full + " bytes: "
                                + p.decision().groups());
            }
        }
    }

    private static Limit limitFor(TurnMode mode) {
        return switch (mode) {
            case ACTION -> new Limit(mode, ACTION_TOOLS, ACTION_BYTES, ACTION_NOTES);
            case GOAL -> new Limit(mode, GOAL_TOOLS, GOAL_BYTES, ACTION_NOTES);
            case CHAT -> new Limit(mode, CHAT_TOOLS, CHAT_BYTES, 0);
            case EVENT -> new Limit(mode, LIGHT_TURN_TOOLS, LIGHT_TURN_BYTES, 0);
            case RECOVERY -> new Limit(mode, RECOVERY_TOOLS, RECOVERY_BYTES, 0);
        };
    }

    @Test
    void theScriptCoversEveryModeAndTheNarrowOnesStayNarrow() {
        FriendaiTools.Builtins builtins = builtins();
        List<ToolSpec> specs = builtins.registry().specs(AccessLevel.OWNER);
        List<Payload> payloads = payloads(new TurnRouter(IntentVocabulary.load()), builtins.catalog(), specs);
        Set<TurnMode> modes = payloads.stream().map(p -> p.decision().mode()).collect(Collectors.toSet());
        assertEquals(
                Set.of(TurnMode.ACTION, TurnMode.GOAL, TurnMode.CHAT, TurnMode.EVENT, TurnMode.RECOVERY),
                modes,
                "every routed mode must be measured here, or a widened branch ships unseen");
        for (Payload p : payloads) {
            if (p.script().text().equals("qwrtp zxcvb") || p.script().turn() == TurnKind.EVENT) {
                assertTrue(
                        p.tools().size() < specs.size() / 2,
                        p.label() + " sends " + p.tools().size() + " of " + specs.size()
                                + " tools: a language without an intent file is not the whole registry");
            }
        }
    }

    @Test
    void everyHeldBackToolIsReachableThroughRequestTools() {
        FriendaiTools.Builtins builtins = builtins();
        ToolCatalog catalog = builtins.catalog();
        TurnRouter router = new TurnRouter(IntentVocabulary.load());
        Payload action = payload(
                router,
                catalog,
                builtins.registry().specs(AccessLevel.OWNER),
                new Script("mine some iron", TurnKind.PLAYER, false));
        Set<String> sent = action.tools().stream().map(ToolSpec::name).collect(Collectors.toSet());
        for (ToolMeta meta : catalog.all()) {
            if (sent.contains(meta.name())) {
                continue;
            }
            assertFalse(meta.group() == ToolGroup.CORE, meta.name() + " is core, so filtering must never drop it");
            assertFalse(
                    ToolGroup.parse(meta.group().name().toLowerCase(java.util.Locale.ROOT))
                            .isEmpty(),
                    meta.name() + " needs a group request_tools accepts");
        }
        String rules = DefaultSections.all().stream()
                .filter(section -> section.id().equals("rules"))
                .findFirst()
                .orElseThrow()
                .render(context())
                .orElseThrow();
        assertTrue(
                rules.contains("request_tools"),
                "the prompt must say how a missing capability is loaded, or hiding a group is a dead end");
    }

    private static TurnContext context() {
        return new TurnContext(
                new Persona("F", "d", "auto"),
                "p",
                new Caller(UUID.randomUUID(), "S", AccessLevel.OWNER),
                "",
                TurnKind.PLAYER,
                "",
                List.of(),
                "");
    }

    private static void writeReport(List<Payload> payloads, List<ToolSpec> specs, ToolCatalog catalog)
            throws IOException {
        StringBuilder report = new StringBuilder("# Routed tool payload\n\n");
        report.append("Generated by `ToolPayloadBudgetTest`. Bytes are the model-facing name + description + schema")
                .append(" of every tool a turn sends; the whole registry is ")
                .append(specs.size())
                .append(" tools / ")
                .append(bytes(specs))
                .append(" bytes, in ")
                .append(catalog.all().stream().map(ToolMeta::group).distinct().count())
                .append(" groups. Tool notes are the `Tool notes:` block the same route renders into the system")
                .append(" prompt.\n\n| turn | mode | groups | tools | bytes | tool notes |\n")
                .append("|---|---|---|---:|---:|---:|\n");
        for (Payload p : payloads) {
            report.append("| ")
                    .append(p.label().replace("|", "\\|"))
                    .append(" | ")
                    .append(p.decision().mode())
                    .append(" | ")
                    .append(p.decision().groups())
                    .append(" | ")
                    .append(p.tools().size())
                    .append(" | ")
                    .append(p.bytes())
                    .append(" | ")
                    .append(p.notes())
                    .append(" |\n");
        }
        report.append("\n## Bytes by group\n\n| group | tools | bytes |\n|---|---:|---:|\n");
        for (ToolGroup group : ToolGroup.values()) {
            List<ToolSpec> inGroup = specs.stream()
                    .filter(spec -> catalog.meta(spec.name())
                            .map(meta -> meta.group() == group)
                            .orElse(false))
                    .toList();
            if (inGroup.isEmpty()) continue;
            report.append("| ")
                    .append(group)
                    .append(" | ")
                    .append(inGroup.size())
                    .append(" | ")
                    .append(bytes(inGroup))
                    .append(" |\n");
        }
        Path out = Path.of("build", "tool-payload-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.toString());
    }
}
