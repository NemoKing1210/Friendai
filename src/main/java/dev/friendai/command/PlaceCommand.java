package dev.friendai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.friendai.common.Components;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.features.places.Place;
import dev.friendai.features.places.PlaceKind;
import dev.friendai.features.places.PlaceLimits;
import dev.friendai.features.places.PlaceNames;
import dev.friendai.features.places.PlaceRegistry;
import dev.friendai.features.places.PlaceSource;
import dev.friendai.features.places.PlaceValues;
import dev.friendai.features.places.minecraft.PlaceService;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** {@code /friendai place ...}: list, add, inspect, rename and remove the sender's own remembered places. */
public final class PlaceCommand {
    /** What a place taught from a command covers: the same radius the tools use for a bare position. */
    private static final int RADIUS = 8;

    private PlaceCommand() {}

    /**
     * Adds the {@code place} subtree under the {@code friendai} command that must already be registered on
     * {@code dispatcher}. Every subcommand works on the sender's own companion and its owner's book.
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = dispatcher.getRoot().getChild("friendai");
        if (root == null) {
            throw new IllegalStateException("The friendai command must be registered before /friendai place");
        }
        root.addChild(command().build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("place")
                .then(Commands.literal("list").executes(PlaceCommand::list))
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> add(ctx, null))
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .executes(ctx -> add(ctx, StringArgumentType.getString(ctx, "kind"))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(PlaceCommand::info)))
                .then(Commands.literal("rename")
                        .then(Commands.argument("old", StringArgumentType.string())
                                .then(Commands.argument("new", StringArgumentType.string())
                                        .executes(PlaceCommand::rename))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(PlaceCommand::remove)));
    }

    /** The sender, their companion and the book the owner reads; {@code dimension} is where the sender stands. */
    private record Session(
            ServerPlayer player,
            CompanionEntity companion,
            PlaceRegistry book,
            String dimension,
            PlaceService places) {}

    /** Empty after sending {@code no_companion}: a player without a companion has no book to change. */
    private static Optional<Session> session(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<CompanionEntity> companion =
                CompanionManager.get(ctx.getSource().getServer()).find(player.getUUID());
        if (companion.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("friendai.command.no_companion"));
            return Optional.empty();
        }
        PlaceService places =
                ServerRuntimeRegistry.getOrCreate(ctx.getSource().getServer()).placeService();
        return Optional.of(new Session(
                player,
                companion.get(),
                places.book(companion.get()),
                player.level().dimension().identifier().toString(),
                places));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Optional<Session> opened = session(ctx);
        if (opened.isEmpty()) {
            return 0;
        }
        Session session = opened.get();
        List<Place> places = session.book().all();
        reply(ctx, "friendai.command.place.list.header", places.size());
        for (Place place : places) {
            reply(
                    ctx,
                    "friendai.command.place.list.entry",
                    place.id(),
                    place.name(),
                    kindWithLabel(place),
                    place.dimension(),
                    place.anchor().x(),
                    place.anchor().y(),
                    place.anchor().z(),
                    place.visits());
        }
        return places.size();
    }

    private static int add(CommandContext<CommandSourceStack> ctx, String rawKind) throws CommandSyntaxException {
        Optional<Session> opened = session(ctx);
        if (opened.isEmpty()) {
            return 0;
        }
        Session session = opened.get();
        String name = StringArgumentType.getString(ctx, "name");
        PlaceKind kind;
        if (rawKind == null) {
            kind = PlaceKind.inferFromName(name);
        } else {
            try {
                kind = PlaceKind.parse(rawKind);
            } catch (IllegalArgumentException unknown) {
                return failure(ctx, "friendai.command.place.kind.unknown", rawKind, kindsText());
            }
        }
        if (!validName(name)) {
            return failure(ctx, "friendai.command.place.name.invalid", PlaceLimits.NAME_MAX);
        }
        PlaceRegistry book = session.book();
        boolean existing = book.find(name, session.dimension()).isPresent();
        int maxPlaces =
                ServerRuntimeRegistry.getOrCreate(ctx.getSource().getServer()).config().places.maxPlaces;
        if (!existing && book.all().size() >= maxPlaces) {
            return failure(ctx, "friendai.command.place.cap", maxPlaces);
        }

        BlockPos anchor = session.player().blockPosition();
        Caller caller = new Caller(
                session.player().getUUID(), session.player().getName().getString(), AccessLevel.OWNER);
        PlaceValues values = new PlaceValues(
                name,
                kind,
                "",
                session.dimension(),
                new Pos(anchor.getX(), anchor.getY(), anchor.getZ()),
                RADIUS,
                Set.of(),
                PlaceSource.COMMAND,
                session.player().getUUID(),
                "");
        Place place = session.places()
                .remember(
                        session.companion(),
                        caller,
                        values,
                        session.player().level().getGameTime());
        reply(
                ctx,
                existing ? "friendai.command.place.updated" : "friendai.command.place.add",
                place.name(),
                kindMessage(place.kind()));
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Optional<Session> opened = session(ctx);
        if (opened.isEmpty()) {
            return 0;
        }
        Session session = opened.get();
        Optional<Place> found = resolve(ctx, session, StringArgumentType.getString(ctx, "name"));
        if (found.isEmpty()) {
            return 0;
        }
        Place place = found.get();
        reply(
                ctx,
                "friendai.command.place.info",
                place.name(),
                kindMessage(place.kind()),
                place.dimension(),
                place.anchor().x(),
                place.anchor().y(),
                place.anchor().z(),
                place.radius(),
                place.visits(),
                sourceMessage(place.source()));
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Optional<Session> opened = session(ctx);
        if (opened.isEmpty()) {
            return 0;
        }
        Session session = opened.get();
        Optional<Place> found = resolve(ctx, session, StringArgumentType.getString(ctx, "old"));
        if (found.isEmpty()) {
            return 0;
        }
        String newName = StringArgumentType.getString(ctx, "new");
        if (!validName(newName)) {
            return failure(ctx, "friendai.command.place.name.invalid", PlaceLimits.NAME_MAX);
        }
        Place place = found.get();
        Optional<Place> taken = session.book().find(newName, session.dimension());
        if (taken.isPresent() && taken.get().id() != place.id()) {
            return failure(ctx, "friendai.command.place.name.taken", newName);
        }
        Place renamed = session.places()
                .rename(
                        session.book(),
                        place.id(),
                        newName,
                        session.player().level().getGameTime());
        reply(ctx, "friendai.command.place.rename", place.name(), renamed.name());
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Optional<Session> opened = session(ctx);
        if (opened.isEmpty()) {
            return 0;
        }
        Session session = opened.get();
        Optional<Place> found = resolve(ctx, session, StringArgumentType.getString(ctx, "name"));
        if (found.isEmpty()) {
            return 0;
        }
        Place place = found.get();
        session.places().forget(session.book(), place.id());
        reply(ctx, "friendai.command.place.remove", place.name());
        return 1;
    }

    /**
     * The one place the sender's dimension knows under this name or alias. Ambiguity cannot arise while the
     * registry keeps one key per name, but reporting it beats guessing if that invariant ever breaks.
     */
    private static Optional<Place> resolve(CommandContext<CommandSourceStack> ctx, Session session, String name) {
        List<Place> hits = matches(session, name);
        if (hits.isEmpty()) {
            failure(ctx, "friendai.command.place.notfound", name);
            return Optional.empty();
        }
        if (hits.size() > 1) {
            failure(
                    ctx,
                    "friendai.command.place.ambiguous",
                    name,
                    hits.stream().map(Place::name).collect(Collectors.joining(", ")));
            return Optional.empty();
        }
        return Optional.of(hits.get(0));
    }

    private static List<Place> matches(Session session, String name) {
        String needle;
        try {
            needle = PlaceNames.normalize(name);
        } catch (IllegalArgumentException notAName) {
            return List.of();
        }
        List<Place> hits = new ArrayList<>();
        for (Place place : session.book().all()) {
            if (!place.dimension().equals(session.dimension())) {
                continue;
            }
            if (PlaceNames.normalize(place.name()).equals(needle)
                    || place.aliases().stream()
                            .anyMatch(alias -> PlaceNames.normalize(alias).equals(needle))) {
                hits.add(place);
            }
        }
        return hits;
    }

    private static boolean validName(String name) {
        try {
            PlaceNames.normalize(name);
        } catch (IllegalArgumentException notAName) {
            return false;
        }
        return name.strip().length() <= PlaceLimits.NAME_MAX;
    }

    /** The kind segment of a list row: the localized kind, plus {@code /label} when the place carries one. */
    private static Component kindWithLabel(Place place) {
        Component kind = Components.of(kindMessage(place.kind()));
        return place.label().isBlank() ? kind : kind.copy().append(Component.literal("/" + place.label()));
    }

    /** The localized kind name; every key is a literal so {@code LangFilesTest} can verify it. */
    private static UserMessage kindMessage(PlaceKind kind) {
        return UserMessage.of(
                switch (kind) {
                    case HOUSE -> "friendai.command.place.kind.house";
                    case FARM -> "friendai.command.place.kind.farm";
                    case MINE -> "friendai.command.place.kind.mine";
                    case STORAGE -> "friendai.command.place.kind.storage";
                    case PORTAL -> "friendai.command.place.kind.portal";
                    case TRADE -> "friendai.command.place.kind.trade";
                    case LANDMARK -> "friendai.command.place.kind.landmark";
                });
    }

    /** The localized origin name; every key is a literal so {@code LangFilesTest} can verify it. */
    private static UserMessage sourceMessage(PlaceSource source) {
        return UserMessage.of(
                switch (source) {
                    case MANUAL -> "friendai.command.place.source.manual";
                    case COMMAND -> "friendai.command.place.source.command";
                    case BUILD -> "friendai.command.place.source.build";
                    case STRUCTURE -> "friendai.command.place.source.structure";
                    case FARM -> "friendai.command.place.source.farm";
                    case MINE -> "friendai.command.place.source.mine";
                });
    }

    /** The raw lowercase kind used by the {@code .kind.unknown} syntax list, not as player-facing prose. */
    private static String kindText(PlaceKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static String kindsText() {
        return Arrays.stream(PlaceKind.values()).map(PlaceCommand::kindText).collect(Collectors.joining(", "));
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        UserMessage message = UserMessage.of(key, args);
        ctx.getSource().sendSuccess(() -> Components.of(message), false);
    }

    private static int failure(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendFailure(Components.of(UserMessage.of(key, args)));
        return 0;
    }
}
