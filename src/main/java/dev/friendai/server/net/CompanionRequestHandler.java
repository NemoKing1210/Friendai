package dev.friendai.server.net;

import com.google.gson.JsonObject;
import dev.friendai.core.CreativeModeService;
import dev.friendai.debug.DebugAccess;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.CompanionRenamer;
import dev.friendai.features.memory.MemoryMenuActions;
import dev.friendai.features.memory.MenuStatus;
import dev.friendai.features.memory.PlayerRef;
import dev.friendai.features.places.Place;
import dev.friendai.features.places.PlaceLimits;
import dev.friendai.features.places.PlaceNames;
import dev.friendai.features.places.PlaceRegistry;
import dev.friendai.protocol.ClientRequest;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.debug.DebugRequest;
import dev.friendai.protocol.debug.PromptProtocolViewCodec;
import dev.friendai.protocol.memory.MemoryRequest;
import dev.friendai.protocol.places.PlaceRequest;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Runs typed menu, memory, places, and debug operations on the server thread. */
public final class CompanionRequestHandler {
    private CompanionRequestHandler() {}

    public static void handle(AuthenticatedRequestContext context, ClientRequest request) {
        ServerPlayer player = context.player();
        ServerRuntime runtime = context.runtime();
        long requestId = context.requestId();
        DebugAccess.Action action = accessAction(request);
        if (action == null) return;
        if (!DebugAccess.allowed(action, Operators.isOperator(player))) {
            player.sendSystemMessage(Component.translatable("friendai.menu.denied"));
            return;
        }

        var server = player.level().getServer();
        UUID owner = player.getUUID();
        if (request instanceof ControlRequest.Subscribe) {
            runtime.debugStream().openMenu(runtime, player, requestId);
            return;
        }
        if (request instanceof ControlRequest.Unsubscribe) {
            runtime.debugStream().closeMenu(runtime, owner);
            return;
        }
        if (request instanceof DebugRequest.MovementStreamSet movement) {
            runtime.movementDebugStream().set(runtime, player, movement.enabled(), requestId);
            return;
        }
        if (request instanceof MemoryRequest.Open) {
            runtime.memoryViewStream().open(runtime, player, requestId);
            return;
        }
        if (request instanceof MemoryRequest.Close) {
            runtime.memoryViewStream().close(runtime, player);
            return;
        }
        if (request instanceof PlaceRequest.Open) {
            runtime.placeViewStream().open(runtime, player, requestId);
            return;
        }
        if (request instanceof PlaceRequest.Close) {
            runtime.placeViewStream().close(runtime, player);
            return;
        }
        if (!runtime.protocolConnections().controlLimiter().tryAcquire(owner)) return;

        CompanionSessions brains = runtime.sessions();
        CompanionManager companions = runtime.companions();
        MenuStatus memoryStatus = null;
        MenuStatus placeStatus = null;

        if (request instanceof ControlRequest.Summon) {
            companions.summon(player);
        } else if (request instanceof ControlRequest.Teleport) {
            companions
                    .find(owner)
                    .ifPresent(c -> c.executionCoordinator().stopWithoutCleanup("teleported to the owner"));
            brains.cancel(owner);
            if (companions.teleportToOwner(player)) brains.onEntityReplaced(owner);
        } else if (request instanceof ControlRequest.Dismiss) {
            brains.freeTime().stop(owner, "dismissed");
            companions.dismiss(player);
        } else if (request instanceof ControlRequest.Rename rename) {
            companions
                    .find(owner)
                    .ifPresentOrElse(
                            c -> {
                                if (CompanionRenamer.rename(c, rename.name()).outcome()
                                        != CompanionRenamer.Outcome.RENAMED) {
                                    player.sendSystemMessage(Component.translatable("friendai.menu.rename.invalid"));
                                }
                            },
                            () -> player.sendSystemMessage(Component.translatable("friendai.menu.no_companion")));
        } else if (request instanceof ControlRequest.SetAutoRespawn toggle) {
            if (companions.find(owner).isEmpty() || (server.isHardcore() && toggle.enabled())) return;
            runtime.companionLife().setAutoRespawn(owner, toggle.enabled());
        } else if (request instanceof ControlRequest.SetCreativeMode toggle) {
            var companion = companions.find(owner);
            if (companion.isEmpty()) {
                player.sendSystemMessage(Component.translatable("friendai.menu.no_companion"));
                return;
            }
            CreativeModeService.Result result =
                    CreativeModeService.requestMode(runtime, companion.get(), player, toggle.enabled());
            if (result == CreativeModeService.Result.DENIED) {
                player.sendSystemMessage(Component.translatable("friendai.menu.creative.denied"));
                runtime.debugStream().sendNow(runtime, player, requestId);
                return;
            }
        } else if (request instanceof ControlRequest.SetFollowMode toggle) {
            if (companions.find(owner).isEmpty()) return;
            runtime.companionLife().setFollowMode(owner, toggle.enabled());
        } else if (request instanceof ControlRequest.FreeTimeToggle) {
            var companion = companions.find(owner);
            if (companion.isEmpty() || brains.freeTime().disabled()) return;
            if (brains.freeTime().state(owner).on()) {
                brains.freeTime().stop(owner, "owner ended free time");
            } else {
                brains.freeTime().start(companion.get(), dev.friendai.agent.autonomy.FreeTime.Origin.COMMAND, "");
            }
        } else if (request instanceof ControlRequest.Stop) {
            companions.find(owner).ifPresent(c -> {
                brains.freeTime().stop(owner, "stopped from menu");
                c.executionCoordinator().stop("stopped from menu");
                brains.cancel(owner);
            });
        } else if (request instanceof DebugRequest.PromptGet) {
            brains.currentPrompt(owner).ifPresent(prompt -> {
                String clipped = prompt.length() > PromptProtocolViewCodec.MAX_PROMPT_BYTES
                        ? prompt.substring(0, PromptProtocolViewCodec.MAX_PROMPT_BYTES)
                        : prompt;
                runtime.protocolConnections()
                        .sendView(player, ProtocolFamily.DEBUG, PromptProtocolViewCodec.encode(clipped, requestId));
            });
        } else if (request instanceof ControlRequest.SendChat chat) {
            String text = chat.text().strip();
            if (!text.isEmpty()) brains.sendMessage(owner, player.getName().getString(), text);
        } else if (request instanceof ControlRequest.InvokeTool tool) {
            brains.runTool(owner, tool.name().strip(), tool.argumentsJson().isBlank() ? "{}" : tool.argumentsJson());
        } else if (request instanceof MemoryRequest memory) {
            memoryStatus = applyMemoryRequest(player, runtime, server, memory);
        } else if (request instanceof PlaceRequest.Rename rename) {
            placeStatus = renamePlace(
                    runtime.placeService(),
                    runtime.places().book(owner),
                    rename.oldName(),
                    rename.newName(),
                    player.level().getGameTime());
        } else if (request instanceof PlaceRequest.Forget forget) {
            placeStatus = forgetPlace(runtime.placeService(), runtime.places().book(owner), forget.name());
        }

        if (memoryStatus != null) {
            runtime.memoryViewStream().sendNow(runtime, player, Optional.of(memoryStatus), requestId);
        }
        if (placeStatus != null) {
            runtime.placeViewStream().sendNow(runtime, player, Optional.of(placeStatus), requestId);
        }
        runtime.debugStream().sendNow(runtime, player, requestId);
    }

    private static DebugAccess.Action accessAction(ClientRequest request) {
        if (request instanceof ControlRequest.Subscribe || request instanceof ControlRequest.Unsubscribe)
            return DebugAccess.Action.VIEW_STATUS;
        if (request instanceof ControlRequest.Summon) return DebugAccess.Action.SUMMON;
        if (request instanceof ControlRequest.Dismiss) return DebugAccess.Action.DISMISS;
        if (request instanceof ControlRequest.Stop) return DebugAccess.Action.STOP;
        if (request instanceof ControlRequest.Teleport) return DebugAccess.Action.TELEPORT;
        if (request instanceof ControlRequest.Rename) return DebugAccess.Action.RENAME;
        if (request instanceof ControlRequest.SetAutoRespawn) return DebugAccess.Action.SET_AUTO_RESPAWN;
        if (request instanceof ControlRequest.SetCreativeMode) return DebugAccess.Action.SET_CREATIVE_MODE;
        if (request instanceof ControlRequest.SetFollowMode) return DebugAccess.Action.SET_FOLLOW_MODE;
        if (request instanceof ControlRequest.FreeTimeToggle) return DebugAccess.Action.SET_FREE_TIME;
        if (request instanceof ControlRequest.SendChat) return DebugAccess.Action.SEND_MESSAGE;
        if (request instanceof ControlRequest.InvokeTool) return DebugAccess.Action.RUN_TOOL;
        if (request instanceof MemoryRequest.Open || request instanceof MemoryRequest.Close)
            return DebugAccess.Action.VIEW_MEMORY;
        if (request instanceof MemoryRequest) return DebugAccess.Action.EDIT_MEMORY;
        if (request instanceof PlaceRequest.Open || request instanceof PlaceRequest.Close)
            return DebugAccess.Action.VIEW_PLACES;
        if (request instanceof PlaceRequest) return DebugAccess.Action.EDIT_PLACES;
        if (request instanceof DebugRequest.PromptGet) return DebugAccess.Action.VIEW_PROMPT;
        if (request instanceof DebugRequest.MovementStreamSet) return DebugAccess.Action.VIEW_MOVEMENT;
        return null;
    }

    private static MenuStatus applyMemoryRequest(
            ServerPlayer player, ServerRuntime runtime, MinecraftServer server, MemoryRequest request) {
        UUID owner = player.getUUID();
        var cfg = runtime.config();
        MemoryMenuActions actions = new MemoryMenuActions(
                runtime.memory().book(owner),
                runtime.profiles(),
                owner,
                player.getName().getString(),
                cfg.memory.enabled,
                cfg.memory.limits(),
                System.currentTimeMillis(),
                uuid -> onlineRef(server, uuid));
        if (request instanceof MemoryRequest.FactAdd add)
            return actions.add(add.groupKey(), memoryDraft(add.categoryId(), add.text()));
        if (request instanceof MemoryRequest.FactEdit edit)
            return actions.edit(Integer.toString(edit.factId()), memoryDraft(edit.categoryId(), edit.text()));
        if (request instanceof MemoryRequest.FactForget forget)
            return actions.forget(Integer.toString(forget.factId()));
        if (request instanceof MemoryRequest.FactClear clear) return actions.clear(clear.groupKey());
        if (request instanceof MemoryRequest.TrustSet trust)
            return actions.trust(trust.target().toString(), Boolean.toString(trust.trusted()));
        if (request instanceof MemoryRequest.PersonaSet persona) return actions.persona(persona.text());
        return null;
    }

    private static String categoryName(int categoryId) {
        return switch (categoryId) {
            case 1 -> "PROFILE";
            case 2 -> "PREFERENCE";
            case 3 -> "PLACE";
            case 4 -> "PROMISE";
            case 5 -> "NOTE";
            default -> throw new IllegalArgumentException("unknown memory category");
        };
    }

    private static String memoryDraft(int categoryId, String text) {
        JsonObject draft = new JsonObject();
        draft.addProperty("category", categoryName(categoryId));
        draft.addProperty("text", text);
        return draft.toString();
    }

    /** Renames the sender's place matched by name or alias; an ambiguous name is reported, never guessed. */
    private static MenuStatus renamePlace(
            dev.friendai.features.places.minecraft.PlaceService places,
            PlaceRegistry book,
            String oldName,
            String newName,
            long tick) {
        List<Place> matches = matches(book, oldName);
        if (matches.isEmpty()) return MenuStatus.error("friendai.menu.places.error.not_found", oldName);
        if (matches.size() > 1)
            return MenuStatus.error("friendai.menu.places.error.ambiguous", oldName, describe(matches));
        Place place = matches.get(0);
        String name = newName == null ? "" : newName.strip();
        if (!validName(name)) return MenuStatus.error("friendai.menu.places.error.name_invalid", PlaceLimits.NAME_MAX);
        Optional<Place> taken = book.find(name, place.dimension());
        if (taken.isPresent() && taken.get().id() != place.id())
            return MenuStatus.error("friendai.menu.places.error.name_taken", name);
        try {
            Place renamed = places.rename(book, place.id(), name, tick);
            return MenuStatus.ok("friendai.menu.places.status.renamed", place.name(), renamed.name());
        } catch (IllegalArgumentException rejected) {
            return MenuStatus.error("friendai.menu.places.error.name_invalid", PlaceLimits.NAME_MAX);
        }
    }

    /** Forgets the sender's place matched by name or alias; an ambiguous name is reported, never guessed. */
    private static MenuStatus forgetPlace(
            dev.friendai.features.places.minecraft.PlaceService places, PlaceRegistry book, String name) {
        List<Place> matches = matches(book, name);
        if (matches.isEmpty()) return MenuStatus.error("friendai.menu.places.error.not_found", name);
        if (matches.size() > 1)
            return MenuStatus.error("friendai.menu.places.error.ambiguous", name, describe(matches));
        Place place = matches.get(0);
        places.forget(book, place.id());
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
