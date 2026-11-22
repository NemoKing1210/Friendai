package dev.friendai.server.agent.coordination;

import dev.friendai.agent.coordination.CompanionClaim;
import dev.friendai.agent.coordination.HandoffBook;
import dev.friendai.agent.coordination.HandoffRequest;
import dev.friendai.agent.coordination.HelpRequest;
import dev.friendai.agent.coordination.HelpRequestBook;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.coordination.minecraft.tool.HandoffTransfer;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-thread facade over derived claims and bounded coordination requests, including owner consent and transfer.
 */
public final class ServerCoordination {
    private final ServerRuntime runtime;
    private final HelpRequestBook requests = new HelpRequestBook();
    private final HandoffBook handoffs = new HandoffBook();

    public ServerCoordination(ServerRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime);
    }

    /** A live job of another owner that repeats this candidate's tool and target, if any. */
    public Optional<CompanionClaim> duplicate(UUID owner, Job candidate) {
        if (owner == null || candidate == null) return Optional.empty();
        for (CompanionEntity companion : runtime.companions().all()) {
            UUID other = companion.ownerUuid();
            if (other == null || other.equals(owner)) {
                continue;
            }
            Optional<CompanionClaim> claim = CompanionClaim.duplicate(
                    other, companion.executionCoordinator().queue().jobs(), candidate);
            if (claim.isPresent()) {
                return claim;
            }
        }
        return Optional.empty();
    }

    public boolean requestHelp(HelpRequest request) {
        long now = clock();
        requests.expire(now);
        if (request == null || request.expired(now) || !requests.request(request)) {
            return false;
        }
        send(request.toOwner(), helpNotice(request, now));
        return true;
    }

    public Optional<HelpRequest> acceptHelp(UUID fromOwner, UUID toOwner, long now) {
        return requests.accept(fromOwner, toOwner, now);
    }

    public boolean declineHelp(UUID fromOwner, UUID toOwner) {
        return requests.open(fromOwner, toOwner, clock()).isPresent() && requests.decline(fromOwner, toOwner);
    }

    public List<HelpRequest> openFor(UUID toOwner, long now) {
        return requests.openFor(toOwner, now);
    }

    public int expire(long now) {
        return requests.expire(now) + handoffs.expire(now);
    }

    public boolean offerHandoff(HandoffRequest request) {
        long now = clock();
        handoffs.expire(now);
        if (request == null || request.expired(now) || !handoffs.offer(request)) {
            return false;
        }
        send(request.toOwner(), handoffNotice(request, now));
        return true;
    }

    public ToolResult acceptHandoff(UUID fromOwner, UUID toOwner, long now) {
        HandoffRequest offer = handoffs.open(fromOwner, toOwner, now).orElse(null);
        if (offer == null) return ToolResult.error("there is no open handoff from that owner");
        if (player(fromOwner) == null || player(toOwner) == null) {
            return ToolResult.error("both owners must be online");
        }
        ToolResult result = HandoffTransfer.move(
                offer,
                runtime.companions().find(fromOwner).orElse(null),
                runtime.companions().find(toOwner).orElse(null));
        if (!result.error()) {
            handoffs.take(fromOwner, toOwner, now);
            var message =
                    Component.translatable("friendai.coordination.handoff_completed", offer.count(), offer.item());
            send(fromOwner, message);
            send(toOwner, message);
        }
        return result;
    }

    public boolean declineHandoff(UUID fromOwner, UUID toOwner) {
        return handoffs.open(fromOwner, toOwner, clock()).isPresent() && handoffs.decline(fromOwner, toOwner);
    }

    public List<HandoffRequest> openHandoffsFor(UUID toOwner, long now) {
        return handoffs.openFor(toOwner, now);
    }

    public int openHandoffCount() {
        return handoffs.size();
    }

    public int openCount() {
        return requests.size();
    }

    public long clock() {
        return runtime.server().getTickCount();
    }

    /** Resends only the executing player's current notices, for example after joining again. */
    public int showIncoming(ServerPlayer recipient) {
        UUID owner = recipient.getUUID();
        if (player(owner) != recipient) return 0;
        long now = clock();
        var help = requests.openFor(owner, now);
        var offers = handoffs.openFor(owner, now);
        help.forEach(request -> recipient.sendSystemMessage(helpNotice(request, now)));
        offers.forEach(offer -> recipient.sendSystemMessage(handoffNotice(offer, now)));
        int count = help.size() + offers.size();
        if (count == 0) recipient.sendSystemMessage(Component.translatable("friendai.coordination.none"));
        return count;
    }

    /** Resolves a chat action against the specific live request addressed to the executing player. */
    public boolean answer(ServerPlayer recipient, UUID id, boolean accept) {
        UUID owner = recipient.getUUID();
        if (player(owner) != recipient) return false;
        long now = clock();
        for (HelpRequest request : requests.openFor(owner, now)) {
            if (request.id().equals(id)) return answerHelp(recipient, request, accept, now);
        }
        for (HandoffRequest offer : handoffs.openFor(owner, now)) {
            if (offer.id().equals(id)) return answerHandoff(recipient, offer, accept, now);
        }
        recipient.sendSystemMessage(Component.translatable("friendai.coordination.unavailable"));
        return false;
    }

    private boolean answerHelp(ServerPlayer recipient, HelpRequest request, boolean accept, long now) {
        Component message;
        if (accept) {
            requests.accept(request.fromOwner(), request.toOwner(), now);
            message =
                    Component.translatable("friendai.coordination.help_accepted", recipient.getName(), request.help());
        } else {
            requests.decline(request.fromOwner(), request.toOwner());
            message = Component.translatable("friendai.coordination.help_declined", recipient.getName());
        }
        recipient.sendSystemMessage(message);
        send(request.fromOwner(), message);
        return true;
    }

    private boolean answerHandoff(ServerPlayer recipient, HandoffRequest offer, boolean accept, long now) {
        if (accept) {
            if (acceptHandoff(offer.fromOwner(), offer.toOwner(), now).error()) {
                recipient.sendSystemMessage(Component.translatable("friendai.coordination.handoff_blocked"));
                return false;
            }
            return true;
        }
        handoffs.decline(offer.fromOwner(), offer.toOwner());
        var message = Component.translatable("friendai.coordination.handoff_declined", recipient.getName());
        recipient.sendSystemMessage(message);
        send(offer.fromOwner(), message);
        return true;
    }

    private Component helpNotice(HelpRequest request, long now) {
        return withActions(
                Component.translatable(
                        "friendai.coordination.help",
                        name(request.fromOwner()),
                        request.help(),
                        secondsLeft(request.expiresAtTick(), now)),
                request.id());
    }

    private Component handoffNotice(HandoffRequest request, long now) {
        return withActions(
                Component.translatable(
                        "friendai.coordination.handoff",
                        name(request.fromOwner()),
                        request.count(),
                        request.item(),
                        request.dimension(),
                        request.x(),
                        request.y(),
                        request.z(),
                        secondsLeft(request.expiresAtTick(), now)),
                request.id());
    }

    private static long secondsLeft(long expiresAt, long now) {
        return Math.ceilDiv(expiresAt - now, 20);
    }

    private static Component withActions(MutableComponent message, UUID id) {
        return message.append(" ")
                .append(action("friendai.coordination.accept", "accept", id, ChatFormatting.GREEN))
                .append(" ")
                .append(action("friendai.coordination.decline", "decline", id, ChatFormatting.RED));
    }

    private static Component action(String key, String verb, UUID id, ChatFormatting color) {
        return Component.translatable(key)
                .withStyle(style -> style.withColor(color)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/friendai coordination " + verb + " " + id)));
    }

    private Component name(UUID owner) {
        ServerPlayer player = player(owner);
        return player == null ? Component.literal(owner.toString()) : player.getName();
    }

    private void send(UUID owner, Component message) {
        ServerPlayer player = player(owner);
        if (player != null) player.sendSystemMessage(message);
    }

    private ServerPlayer player(UUID owner) {
        return runtime.server().getPlayerList().getPlayer(owner);
    }
}
