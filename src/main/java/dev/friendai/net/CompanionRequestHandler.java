package dev.friendai.net;

import dev.friendai.core.BrainService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.DebugAccess;
import dev.friendai.debug.RequestType;
import dev.friendai.entity.CompanionManager;
import dev.friendai.memory.MemoryMenuActions;
import dev.friendai.memory.MenuStatus;
import dev.friendai.memory.PlayerRef;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server side of the companion menu: checks permissions and carries out the request. Runs on the server thread. */
public final class CompanionRequestHandler {
    private static volatile RateLimiter limiter = new RateLimiter(300, System::currentTimeMillis);

    private CompanionRequestHandler() {}

    /** Test hook: replace the rate limiter. */
    public static void setLimiter(RateLimiter replacement) {
        limiter = replacement;
    }

    public static void handle(ServerPlayer player, CompanionRequestC2SPayload req) {
        Optional<DebugAccess.Action> action = req.request().action();
        if (action.isEmpty()) {
            return;
        }
        if (!DebugAccess.allowed(action.get(), Operators.isOperator(player))) {
            player.sendSystemMessage(Component.translatable("friendai.menu.denied"));
            return;
        }
        String a = req.a() == null ? "" : req.a();
        String b = req.b() == null ? "" : req.b();
        if (a.length() > CompanionRequestC2SPayload.MAX_A || b.length() > CompanionRequestC2SPayload.MAX_B) {
            return;
        }
        var server = player.level().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        BrainService brains = state.brains();
        UUID owner = player.getUUID();
        RequestType type = req.request();
        if (type == RequestType.SUBSCRIBE) {
            brains.debug().subscribe(owner);
            DebugStream.sendNow(player);
            return;
        }
        if (type == RequestType.UNSUBSCRIBE) {
            brains.debug().unsubscribe(owner);
            return;
        }
        if (type == RequestType.MEMORY_OPEN) {
            MemoryViewStream.open(player);
            return;
        }
        if (type == RequestType.MEMORY_CLOSE) {
            MemoryViewStream.close(player);
            return;
        }
        if (!limiter.tryAcquire(owner)) {
            return;
        }
        MenuStatus memoryStatus = null;
        CompanionManager companions = CompanionManager.get(server);
        switch (type) {
            case SUMMON -> companions.summon(player);
            case TELEPORT -> {
                companions.find(owner).ifPresent(c -> c.taskRunner().stopWithoutCleanup("teleported to the owner"));
                brains.cancel(owner);
                if (companions.teleportToOwner(player)) {
                    // Cross-dimension teleports replace the entity, while a brain captures its entity-bound tools.
                    brains.onCompanionReplaced(owner);
                }
            }
            case DISMISS -> companions.dismiss(player);
            case STOP -> companions.find(owner).ifPresent(c -> {
                c.taskRunner().stop("stopped from menu");
                brains.cancel(owner);
            });
            case REQUEST_PROMPT -> brains.currentPrompt(owner).ifPresent(prompt -> {
                if (ServerPlayNetworking.canSend(player, PromptS2CPayload.TYPE)) {
                    String clipped = prompt.length() > PromptS2CPayload.MAX_CHARS
                            ? prompt.substring(0, PromptS2CPayload.MAX_CHARS)
                            : prompt;
                    ServerPlayNetworking.send(player, new PromptS2CPayload(clipped));
                }
            });
            case SEND_MESSAGE -> {
                String text = a.strip();
                if (!text.isEmpty()) {
                    brains.sendMessage(owner, player.getName().getString(), text);
                }
            }
            case RUN_TOOL -> brains.runTool(owner, a.strip(), b.isBlank() ? "{}" : b);
            case MEMORY_ADD, MEMORY_EDIT, MEMORY_FORGET, MEMORY_CLEAR, MEMORY_TRUST -> {
                var cfg = state.config();
                MemoryMenuActions actions = new MemoryMenuActions(
                        state.memory().book(owner),
                        owner,
                        player.getName().getString(),
                        cfg.memory.enabled,
                        cfg.memory.limits(),
                        System.currentTimeMillis(),
                        uuid -> onlineRef(server, uuid));
                memoryStatus = switch (type) {
                    case MEMORY_ADD -> actions.add(a, b);
                    case MEMORY_EDIT -> actions.edit(a, b);
                    case MEMORY_FORGET -> actions.forget(a);
                    case MEMORY_CLEAR -> actions.clear(a);
                    case MEMORY_TRUST -> actions.trust(a, b);
                    default -> null;
                };
            }
            default -> {}
        }
        if (memoryStatus != null) {
            MemoryViewStream.sendNow(player, Optional.of(memoryStatus));
        }
        DebugStream.sendNow(player);
    }

    private static Optional<PlayerRef> onlineRef(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        return online == null
                ? Optional.empty()
                : Optional.of(new PlayerRef(uuid, online.getName().getString()));
    }
}
