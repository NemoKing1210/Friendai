package dev.friedai.net;

import dev.friedai.core.BrainService;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.debug.DebugAccess;
import dev.friedai.debug.RequestType;
import dev.friedai.entity.CompanionManager;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
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
            player.sendSystemMessage(Component.translatable("friedai.menu.denied"));
            return;
        }
        String a = req.a() == null ? "" : req.a();
        String b = req.b() == null ? "" : req.b();
        if (a.length() > CompanionRequestC2SPayload.MAX_A || b.length() > CompanionRequestC2SPayload.MAX_B) {
            return;
        }
        var server = player.level().getServer();
        FriedaiServerState state = FriedaiServerState.get(server);
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
        if (!limiter.tryAcquire(owner)) {
            return;
        }
        CompanionManager companions = CompanionManager.get(server);
        switch (type) {
            case SUMMON -> companions
                    .summon(player)
                    .setCustomName(Component.literal(state.config().companion.name));
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
            default -> {}
        }
        DebugStream.sendNow(player);
    }
}
