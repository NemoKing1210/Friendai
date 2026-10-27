package dev.friendai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.friendai.FriendaiMod;
import dev.friendai.config.ServerConfig;
import dev.friendai.core.Components;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.memory.MemoryBook;
import dev.friendai.memory.MemoryReport;
import dev.friendai.memory.PlayerRecord;
import dev.friendai.menu.FriendaiMenus;
import dev.friendai.task.Task;
import dev.friendai.text.UserMessage;
import dev.friendai.tool.InventoryAccess;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class FriendaiCommands {
    private FriendaiCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> register(dispatcher));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("friendai")
                .then(Commands.literal("summon").executes(FriendaiCommands::summon))
                .then(Commands.literal("dismiss").executes(FriendaiCommands::dismiss))
                .then(Commands.literal("stop").executes(FriendaiCommands::stop))
                .then(Commands.literal("inventory").executes(FriendaiCommands::inventory))
                .then(Commands.literal("status").executes(FriendaiCommands::status))
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(FriendaiCommands::trust)))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (PlayerRecord record : book(ctx.getSource()).trusted()) {
                                        builder.suggest(record.lastName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(FriendaiCommands::untrust)))
                .then(Commands.literal("trusted").executes(FriendaiCommands::trusted))
                .then(Commands.literal("pending")
                        .then(Commands.literal("confirm")
                                .then(Commands.argument("id", IntegerArgumentType.integer())
                                        .executes(ctx -> pending(ctx, true))))
                        .then(Commands.literal("cancel")
                                .then(Commands.argument("id", IntegerArgumentType.integer())
                                        .executes(ctx -> pending(ctx, false)))))
                .then(memoryCommand())
                .then(Commands.literal("reload")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(FriendaiCommands::reload)));
        PlaceCommand.register(dispatcher);
    }

    private static int summon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var server = ctx.getSource().getServer();
        CompanionEntity companion = CompanionManager.get(server).summon(player);
        if (companion.level() != player.level()) {
            ctx.getSource().sendFailure(Component.translatable("friendai.command.summon_other_dimension"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("friendai.command.summoned", companion.getName()), false);
        return 1;
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CompanionManager manager = CompanionManager.get(ctx.getSource().getServer());
        Component name = manager.find(player.getUUID()).map(CompanionEntity::getName).orElse(Component.empty());
        if (manager.dismiss(player)) {
            ctx.getSource().sendSuccess(() -> Component.translatable("friendai.command.dismissed", name), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.translatable("friendai.command.no_companion"));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var server = ctx.getSource().getServer();
        var companion = CompanionManager.get(server).find(player.getUUID());
        if (companion.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("friendai.command.no_companion"));
            return 0;
        }
        companion.get().taskRunner().stop("stopped by command");
        FriendaiServerState.get(server).brains().freeTime().stop(player.getUUID(), "stopped by command");
        FriendaiServerState.get(server).brains().cancel(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable("friendai.command.stopped"), false);
        return 1;
    }

    private static int inventory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var companion = CompanionManager.get(ctx.getSource().getServer()).find(player.getUUID());
        if (companion.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("friendai.command.no_companion"));
            return 0;
        }
        InventoryAccess.Denial denial = FriendaiMenus.open(player, companion.get());
        if (denial != InventoryAccess.Denial.NONE) {
            ctx.getSource().sendFailure(Component.translatable(denial == InventoryAccess.Denial.NOT_OWNER
                    ? "friendai.inventory.not_owner"
                    : "friendai.inventory.unavailable"));
            return 0;
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var server = ctx.getSource().getServer();
        var state = FriendaiServerState.get(server);
        var companion = CompanionManager.get(server).find(player.getUUID());
        if (companion.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("friendai.command.no_companion"));
            return 0;
        }
        String task = companion.get().taskRunner().current().map(Task::describe).orElse("none");
        boolean thinking = state.brains().brainOf(player.getUUID()).map(b -> b.isThinking()).orElse(false);
        UserMessage message = UserMessage.of(
                "friendai.command.status",
                state.brains().activeProvider(),
                state.brains().activeModel(),
                task,
                UserMessage.of(thinking ? "friendai.common.yes" : "friendai.common.no"));
        ctx.getSource().sendSuccess(() -> Components.of(message), false);
        return 1;
    }

    private static int trust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (target.getUUID().equals(sender.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable("friendai.command.trust.self"));
            return 0;
        }
        String name = target.getName().getString();
        boolean changed = book(ctx.getSource())
                .setTrusted(target.getUUID(), name, true, System.currentTimeMillis());
        ctx.getSource()
                .sendSuccess(
                        () -> Component.translatable(
                                changed ? "friendai.command.trust.added" : "friendai.command.trust.already", name),
                        false);
        return changed ? 1 : 0;
    }

    private static int untrust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        var book = FriendaiServerState.get(ctx.getSource().getServer()).memory().book(sender.getUUID());
        var record = book.findByName(name);
        if (record.isEmpty() || !record.get().trusted()) {
            ctx.getSource().sendFailure(Component.translatable("friendai.command.untrust.unknown", name));
            return 0;
        }
        book.setTrusted(record.get().uuid(), record.get().lastName(), false, System.currentTimeMillis());
        ctx.getSource()
                .sendSuccess(
                        () -> Component.translatable("friendai.command.untrust.removed", record.get().lastName()),
                        false);
        return 1;
    }

    private static int trusted(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        List<PlayerRecord> records = FriendaiServerState.get(ctx.getSource().getServer())
                .memory()
                .book(sender.getUUID())
                .trusted();
        if (records.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("friendai.command.trusted.none"), false);
            return 0;
        }
        String names = records.stream().map(PlayerRecord::lastName).collect(Collectors.joining(", "));
        ctx.getSource()
                .sendSuccess(() -> Component.translatable("friendai.command.trusted.list", names), false);
        return records.size();
    }

    /** Applies or cancels the player's own pending trust request; the buttons run these commands. */
    private static int pending(CommandContext<CommandSourceStack> ctx, boolean confirm) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        boolean applied = FriendaiServerState.get(ctx.getSource().getServer())
                .trust()
                .apply(player.getUUID(), id, confirm);
        if (!applied) {
            ctx.getSource().sendFailure(Component.translatable("friendai.trust.none"));
            return 0;
        }
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> memoryCommand() {
        return Commands.literal("memory")
                .then(Commands.literal("list")
                        .executes(ctx -> memoryList(ctx, null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    suggestMemoryTargets(ctx.getSource(), builder, false);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> memoryList(ctx, StringArgumentType.getString(ctx, "target")))))
                .then(Commands.literal("forget")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> memoryForget(ctx, IntegerArgumentType.getInteger(ctx, "id")))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    suggestMemoryTargets(ctx.getSource(), builder, true);
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> memoryClear(ctx, StringArgumentType.getString(ctx, "target")))));
    }

    private static void suggestMemoryTargets(
            CommandSourceStack source, SuggestionsBuilder builder, boolean withAll) {
        if (withAll) {
            builder.suggest("all");
        }
        builder.suggest("world");
        try {
            ServerPlayer sender = source.getPlayerOrException();
            for (PlayerRecord record : FriendaiServerState.get(source.getServer())
                    .memory()
                    .book(sender.getUUID())
                    .players()) {
                if (record.lastName() != null && !record.lastName().isBlank()) {
                    builder.suggest(record.lastName());
                }
            }
        } catch (CommandSyntaxException e) {
            // The console has no book: only the literals are suggested.
        }
    }

    private static int memoryList(CommandContext<CommandSourceStack> ctx, String target)
            throws CommandSyntaxException {
        if (!memoryEnabled(ctx)) {
            return 0;
        }
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        MemoryBook book = FriendaiServerState.get(ctx.getSource().getServer())
                .memory()
                .book(sender.getUUID());
        for (UserMessage message : MemoryReport.list(book, sender.getUUID(), target)) {
            ctx.getSource().sendSuccess(() -> Components.of(message), false);
        }
        return 1;
    }

    private static int memoryForget(CommandContext<CommandSourceStack> ctx, int id)
            throws CommandSyntaxException {
        if (!memoryEnabled(ctx)) {
            return 0;
        }
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        MemoryBook book = FriendaiServerState.get(ctx.getSource().getServer())
                .memory()
                .book(sender.getUUID());
        UserMessage message = MemoryReport.forget(book, id);
        ctx.getSource().sendSuccess(() -> Components.of(message), false);
        return 1;
    }

    private static int memoryClear(CommandContext<CommandSourceStack> ctx, String target)
            throws CommandSyntaxException {
        if (!memoryEnabled(ctx)) {
            return 0;
        }
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        MemoryBook book = FriendaiServerState.get(ctx.getSource().getServer())
                .memory()
                .book(sender.getUUID());
        UserMessage message = MemoryReport.clear(book, target);
        ctx.getSource().sendSuccess(() -> Components.of(message), false);
        return 1;
    }

    private static boolean memoryEnabled(CommandContext<CommandSourceStack> ctx) {
        if (FriendaiServerState.get(ctx.getSource().getServer()).config().memory.enabled) {
            return true;
        }
        ctx.getSource().sendFailure(Component.translatable("friendai.command.memory.disabled"));
        return false;
    }

    /** The sender's memory book: trust belongs to the owner, not to a companion entity. */
    private static MemoryBook book(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer sender = source.getPlayerOrException();
        return FriendaiServerState.get(source.getServer()).memory().book(sender.getUUID());
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var state = FriendaiServerState.get(ctx.getSource().getServer());
        ServerConfig loaded = state.store().load();
        List<String> problems = state.applyConfig(loaded);
        problems.forEach(p -> FriendaiMod.LOGGER.warn("Config reload: {}", p));
        ctx.getSource().sendSuccess(
                () -> problems.isEmpty()
                        ? Component.translatable("friendai.command.reloaded")
                        : Component.translatable("friendai.command.reloaded_with_fixes", problems.size()),
                true);
        return 1;
    }
}
