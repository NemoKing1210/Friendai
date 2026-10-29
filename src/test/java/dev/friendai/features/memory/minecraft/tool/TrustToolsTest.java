package dev.friendai.features.memory.minecraft.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolRegistry;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.PlayerRecord;
import dev.friendai.features.memory.PlayerRef;
import dev.friendai.features.memory.TrustRequest;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TrustToolsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final Set<String> NAMES = Set.of("trust_player", "untrust_player");

    @Test
    void trustToolsAreOwnerOnlyAndMatchTheCatalog() {
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        FeatureToolRegistry registry = new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Trust tools are not task-backed");
        });

        new TrustTools(new TrustAccessStub()).register(registry);

        assertTrue(tools.specs(AccessLevel.GUEST).isEmpty());
        assertTrue(tools.specs(AccessLevel.TRUSTED).isEmpty());
        assertEquals(NAMES, names(tools.specs(AccessLevel.OWNER)));
        JsonObject expectedSchema = JsonParser.parseString(
                        "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"The player's name\"}},\"required\":[\"name\"]}")
                .getAsJsonObject();
        tools.specs(AccessLevel.OWNER).forEach(spec -> assertEquals(expectedSchema, spec.parametersSchema()));
        ToolCatalog catalog = ToolCatalog.defaults();
        registry.catalogMetadata()
                .forEach(meta -> assertEquals(catalog.meta(meta.name()).orElseThrow(), meta));
    }

    @Test
    void trustRequestUsesCanonicalOnlineNameAndOwnerConfirmationMessage() {
        TrustAccessStub access = new TrustAccessStub();
        access.online = Optional.of(new PlayerRef(TARGET, "Bob"));
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        new TrustTools(access).register(new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Trust tools are not task-backed");
        }));

        ToolResult result = call(tools, "trust_player", "  bOb  ");

        assertFalse(result.error());
        assertEquals("asked the owner to confirm (request #17, expires in 60 s)", result.content());
        assertEquals("Bob", access.requestedName);
        assertEquals(TARGET, access.requestedTarget);
        assertTrue(access.requestedTrust);
    }

    @Test
    void knownOfflinePlayerCanBeTrustedByTheOwner() {
        TrustAccessStub access = new TrustAccessStub();
        access.known = Optional.of(new PlayerRecord(TARGET, "Bob", false, 12));
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        new TrustTools(access).register(new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Trust tools are not task-backed");
        }));

        ToolResult result = call(tools, "trust_player", "Bob");

        assertEquals("asked the owner to confirm (request #17, expires in 60 s)", result.content());
        assertEquals(TARGET, access.requestedTarget);
        assertEquals("Bob", access.requestedName);
        assertTrue(access.requestedTrust);
    }

    @Test
    void trustToolPreservesOwnerAndTargetFailureMessages() {
        TrustAccessStub access = new TrustAccessStub();
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        new TrustTools(access).register(new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Trust tools are not task-backed");
        }));

        access.owner = null;
        assertEquals(
                "I don't have an owner.", call(tools, "trust_player", "Bob").content());
        access.owner = OWNER;
        access.ownerOnline = false;
        assertEquals("the owner is offline", call(tools, "trust_player", "Bob").content());
        access.ownerOnline = true;
        access.online = Optional.empty();
        access.known = Optional.empty();
        assertEquals(
                "I don't know a player called Bob",
                call(tools, "trust_player", "Bob").content());
        access.online = Optional.of(new PlayerRef(OWNER, "Owner"));
        assertEquals(
                "you are the owner already",
                call(tools, "trust_player", "Owner").content());
    }

    @Test
    void trustToolReportsWhenTheRequestedTrustStateAlreadyMatches() {
        TrustAccessStub access = new TrustAccessStub();
        access.online = Optional.of(new PlayerRef(TARGET, "Bob"));
        access.trusted = true;
        ToolRegistry<CompanionEntity> tools = new ToolRegistry<>();
        new TrustTools(access).register(new FeatureToolRegistry(tools, definition -> {
            throw new AssertionError("Trust tools are not task-backed");
        }));

        assertEquals(
                "Bob is already trusted", call(tools, "trust_player", "Bob").content());
        access.trusted = false;
        assertEquals("Bob is not trusted", call(tools, "untrust_player", "Bob").content());
    }

    private static ToolResult call(ToolRegistry<CompanionEntity> tools, String name, String playerName) {
        JsonObject args = new JsonObject();
        args.addProperty("name", playerName);
        return tools.bind(null)
                .execute(
                        new ToolCall("id", name, args, args.toString(), null),
                        new Caller(OWNER, "Steve", AccessLevel.OWNER));
    }

    private static Set<String> names(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).collect(Collectors.toSet());
    }

    private static final class TrustAccessStub implements TrustAccess {
        private UUID owner = OWNER;
        private boolean ownerOnline = true;
        private boolean trusted;
        private Optional<PlayerRef> online = Optional.empty();
        private Optional<PlayerRecord> known = Optional.empty();
        private String requestedName;
        private UUID requestedTarget;
        private boolean requestedTrust;

        @Override
        public UUID ownerUuid(CompanionEntity companion) {
            return owner;
        }

        @Override
        public boolean ownerOnline(CompanionEntity companion) {
            return ownerOnline;
        }

        @Override
        public Optional<PlayerRef> findOnlinePlayer(String name) {
            return online.filter(player -> player.name().equalsIgnoreCase(name));
        }

        @Override
        public Optional<PlayerRecord> findKnownPlayer(UUID owner, String name) {
            return known.filter(player -> player.lastName().equalsIgnoreCase(name));
        }

        @Override
        public boolean isTrusted(UUID owner, UUID target) {
            return trusted;
        }

        @Override
        public TrustRequest request(CompanionEntity companion, UUID target, String targetName, boolean trust) {
            requestedName = targetName;
            requestedTarget = target;
            requestedTrust = trust;
            return new TrustRequest(17, OWNER, target, targetName, trust, 0);
        }
    }
}
