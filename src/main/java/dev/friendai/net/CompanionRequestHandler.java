package dev.friendai.net;

import dev.friendai.core.CreativeModeService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.DebugAccess;
import dev.friendai.debug.RequestType;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.CompanionRenamer;
import dev.friendai.memory.MemoryMenuActions;
import dev.friendai.memory.MenuStatus;
import dev.friendai.memory.PlayerRef;
import dev.friendai.place.Place;
import dev.friendai.place.PlaceLimits;
import dev.friendai.place.PlaceNames;
import dev.friendai.place.PlaceRegistry;
import dev.friendai.server.agent.CompanionSessions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
        CompanionSessions brains = state.sessions();
        UUID owner = player.getUUID();
        RequestType type = req.request();
        if (type == RequestType.SUBSCRIBE) {
            DebugStream.openMenu(player);
            return;
        }
        if (type == RequestType.UNSUBSCRIBE) {
            DebugStream.closeMenu(server, owner);
            return;
        }
        if (type == RequestType.MOVE_DEBUG_ON || type == RequestType.MOVE_DEBUG_OFF) {
            MovementDebugStream.set(player, type == RequestType.MOVE_DEBUG_ON);
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
        if (type == RequestType.PLACES_OPEN) {
            PlaceViewStream.open(player);
            return;
        }
        if (type == RequestType.PLACES_CLOSE) {
            PlaceViewStream.close(player);
            return;
        }
        if (!limiter.tryAcquire(owner)) {
            return;
        }
        MenuStatus memoryStatus = null;
        MenuStatus placeStatus = null;
        CompanionManager companions = CompanionManager.get(server);
        switch (type) {
            case SUMMON -> companions.summon(player);
            case TELEPORT -> {
                companions.find(owner).ifPresent(c -> c.taskRunner().stopWithoutCleanup("teleported to the owner"));
                brains.cancel(owner);
                if (companions.teleportToOwner(player)) {
                    // Cross-dimension teleports replace the entity, while a brain captures its entity-bound tools.
                    brains.onEntityReplaced(owner);
                }
            }
            case DISMISS -> {
                brains.freeTime().stop(owner, "dismissed");
                companions.dismiss(player);
            }
            case RENAME ->
                companions
                        .find(owner)
                        .ifPresentOrElse(
                                c -> {
                                    if (CompanionRenamer.rename(c, a).outcome() != CompanionRenamer.Outcome.RENAMED) {
                                        player.sendSystemMessage(
                                                Component.translatable("friendai.menu.rename.invalid"));
                                    }
                                },
                                () -> player.sendSystemMessage(Component.translatable("friendai.menu.no_companion")));
            case SET_AUTO_RESPAWN -> {
                if (!a.equals("true") && !a.equals("false")) {
                    return;
                }
                if (companions.find(owner).isEmpty()) {
                    return;
                }
                boolean enabled = a.equals("true");
                if (server.isHardcore() && enabled) {
                    return;
                }
                state.companionLife().setAutoRespawn(owner, enabled);
            }
            case SET_CREATIVE_MODE -> {
                if (!a.equals("true") && !a.equals("false")) {
                    return;
                }
                var companion = companions.find(owner);
                if (companion.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("friendai.menu.no_companion"));
                    return;
                }
                CreativeModeService.Result result =
                        CreativeModeService.requestMode(companion.get(), player, a.equals("true"));
                if (result == CreativeModeService.Result.DENIED) {
                    player.sendSystemMessage(Component.translatable("friendai.menu.creative.denied"));
                    DebugStream.sendNow(player);
                    return;
                }
            }
            case SET_FOLLOW_MODE -> {
                if (!a.equals("true") && !a.equals("false")) {
                    return;
                }
                if (companions.find(owner).isEmpty()) {
                    return;
                }
                state.companionLife().setFollowMode(owner, a.equals("true"));
            }
            case FREE_TIME_TOGGLE -> {
                var companion = companions.find(owner);
                if (companion.isEmpty() || brains.freeTime().disabled()) {
                    return;
                }
                if (brains.freeTime().state(owner).on()) {
                    brains.freeTime().stop(owner, "owner ended free time");
                } else {
                    brains.freeTime().start(companion.get(), dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
                }
            }
            case STOP ->
                companions.find(owner).ifPresent(c -> {
                    brains.freeTime().stop(owner, "stopped from menu");
                    c.taskRunner().stop("stopped from menu");
                    brains.cancel(owner);
                });
            case REQUEST_PROMPT ->
                brains.currentPrompt(owner).ifPresent(prompt -> {
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
            case MEMORY_ADD, MEMORY_EDIT, MEMORY_FORGET, MEMORY_CLEAR, MEMORY_TRUST, PERSONA_SET -> {
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
                    case PERSONA_SET -> actions.persona(a);
                    default -> null;
                };
            }
            case PLACES_RENAME ->
                placeStatus = renamePlace(
                        state.places().book(owner), a, b, player.level().getGameTime());
            case PLACES_FORGET -> placeStatus = forgetPlace(state.places().book(owner), a);
            default -> {}
        }
        if (memoryStatus != null) {
            MemoryViewStream.sendNow(player, Optional.of(memoryStatus));
        }
        if (placeStatus != null) {
            PlaceViewStream.sendNow(player, Optional.of(placeStatus));
        }
        DebugStream.sendNow(player);
    }

    /** Renames the sender's place matched by name or alias; an ambiguous name is reported, never guessed. */
    private static MenuStatus renamePlace(PlaceRegistry book, String oldName, String newName, long tick) {
        List<Place> matches = matches(book, oldName);
        if (matches.isEmpty()) {
            return MenuStatus.error("friendai.menu.places.error.not_found", oldName);
        }
        if (matches.size() > 1) {
            return MenuStatus.error("friendai.menu.places.error.ambiguous", oldName, describe(matches));
        }
        Place place = matches.get(0);
        String name = newName == null ? "" : newName.strip();
        if (!validName(name)) {
            return MenuStatus.error("friendai.menu.places.error.name_invalid", PlaceLimits.NAME_MAX);
        }
        Optional<Place> taken = book.find(name, place.dimension());
        if (taken.isPresent() && taken.get().id() != place.id()) {
            return MenuStatus.error("friendai.menu.places.error.name_taken", name);
        }
        try {
            Place renamed = book.rename(place.id(), name, tick);
            return MenuStatus.ok("friendai.menu.places.status.renamed", place.name(), renamed.name());
        } catch (IllegalArgumentException rejected) {
            return MenuStatus.error("friendai.menu.places.error.name_invalid", PlaceLimits.NAME_MAX);
        }
    }

    /** Forgets the sender's place matched by name or alias; an ambiguous name is reported, never guessed. */
    private static MenuStatus forgetPlace(PlaceRegistry book, String name) {
        List<Place> matches = matches(book, name);
        if (matches.isEmpty()) {
            return MenuStatus.error("friendai.menu.places.error.not_found", name);
        }
        if (matches.size() > 1) {
            return MenuStatus.error("friendai.menu.places.error.ambiguous", name, describe(matches));
        }
        Place place = matches.get(0);
        book.remove(place.id());
        return MenuStatus.ok("friendai.menu.places.status.forgotten", place.name());
    }

    /** Every place the book knows under this name or alias, across dimensions. */
    private static List<Place> matches(PlaceRegistry book, String name) {
        String needle;
        try {
            needle = PlaceNames.normalize(name);
        } catch (IllegalArgumentException notAName) {
            return List.of();
        }
        List<Place> hits = new ArrayList<>();
        for (Place place : book.all()) {
            if (PlaceNames.normalize(place.name()).equals(needle)
                    || place.aliases().stream()
                            .anyMatch(alias -> PlaceNames.normalize(alias).equals(needle))) {
                hits.add(place);
            }
        }
        return hits;
    }

    /** Names with their dimension, so an ambiguity between two worlds reads as two distinct places. */
    private static String describe(List<Place> places) {
        return places.stream()
                .map(place -> place.name() + " (" + place.dimension() + ")")
                .collect(Collectors.joining(", "));
    }

    private static boolean validName(String name) {
        try {
            PlaceNames.normalize(name);
        } catch (IllegalArgumentException notAName) {
            return false;
        }
        return name.strip().length() <= PlaceLimits.NAME_MAX;
    }

    private static Optional<PlayerRef> onlineRef(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        return online == null
                ? Optional.empty()
                : Optional.of(new PlayerRef(uuid, online.getName().getString()));
    }
}
