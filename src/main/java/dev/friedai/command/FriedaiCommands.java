package dev.friedai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.friedai.FriedaiMod;
import dev.friedai.config.ServerConfig;
import dev.friedai.core.Components;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
import dev.friedai.memory.MemoryBook;
import dev.friedai.memory.PlayerRecord;
import dev.friedai.task.Task;
import dev.friedai.text.UserMessage;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class FriedaiCommands {
    private FriedaiCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) -> register(dispatcher));
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("friedai")
                .then(Commands.literal("summon").executes(FriedaiCommands::summon))
                .then(Commands.literal("dismiss").executes(FriedaiCommands::dismiss))
                .then(Commands.literal("stop").executes(FriedaiCommands::stop))
                .then(Commands.literal("status").executes(FriedaiCommands::status))
                .then(Commands.literal("trust")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(FriedaiCommands::trust)))
                .then(Commands.literal("untrust")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (PlayerRecord record : book(ctx.getSource()).trusted()) {
                                        builder.suggest(record.lastName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(FriedaiCommands::untrust)))
                .then(Commands.literal("trusted").executes(FriedaiCommands::trusted))
                .then(Commands.literal("reload")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(FriedaiCommands::reload)));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var server = ctx.getSource().getServer();
        CompanionEntity companion = CompanionManager.get(server).summon(player);
        companion.setCustomName(Component.literal(FriedaiServerState.get(server).config().companion.name));
        ctx.getSource().sendSuccess(() -> Component.translatable("friedai.command.summoned", companion.getName()), false);
        return 1;
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CompanionManager manager = CompanionManager.get(ctx.getSource().getServer());
        Component name = manager.find(player.getUUID()).map(CompanionEntity::getName).orElse(Component.empty());
        if (manager.dismiss(player)) {
            ctx.getSource().sendSuccess(() -> Component.translatable("friedai.command.dismissed", name), false);
            return 1;
        }
        ctx.getSource().sendFailure(Component.translatable("friedai.command.no_companion"));
        return 0;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var server = ctx.getSource().getServer();
        var companion = CompanionManager.get(server).find(player.getUUID());
        if (companion.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("friedai.command.no_companion"));
            return 0;
        }
        companion.get().taskRunner().stop("stopped by command");
        FriedaiServerState.get(server).brains().cancel(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable("friedai.command.stopped"), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var server = ctx.getSource().getServer();
        var state = FriedaiServerState.get(server);
        var companion = CompanionManager.get(server).find(player.getUUID());
        if (companion.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("friedai.command.no_companion"));
            return 0;
        }
        String task = companion.get().taskRunner().current().map(Task::describe).orElse("none");
        boolean thinking = state.brains().brainOf(player.getUUID()).map(b -> b.isThinking()).orElse(false);
        UserMessage message = UserMessage.of(
                "friedai.command.status",
                state.brains().activeProvider(),
                state.brains().activeModel(),
                task,
                UserMessage.of(thinking ? "friedai.common.yes" : "friedai.common.no"));
        ctx.getSource().sendSuccess(() -> Components.of(message), false);
        return 1;
    }

    private static int trust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        if (target.getUUID().equals(sender.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable("friedai.command.trust.self"));
            return 0;
        }
        String name = target.getName().getString();
        boolean changed = book(ctx.getSource())
                .setTrusted(target.getUUID(), name, true, System.currentTimeMillis());
        ctx.getSource()
                .sendSuccess(
                        () -> Component.translatable(
                                changed ? "friedai.command.trust.added" : "friedai.command.trust.already", name),
                        false);
        return changed ? 1 : 0;
    }

    private static int untrust(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        var book = FriedaiServerState.get(ctx.getSource().getServer()).memory().book(sender.getUUID());
        var record = book.findByName(name);
        if (record.isEmpty() || !record.get().trusted()) {
            ctx.getSource().sendFailure(Component.translatable("friedai.command.untrust.unknown", name));
            return 0;
        }
        book.setTrusted(record.get().uuid(), record.get().lastName(), false, System.currentTimeMillis());
        ctx.getSource()
                .sendSuccess(
                        () -> Component.translatable("friedai.command.untrust.removed", record.get().lastName()),
                        false);
        return 1;
    }

    private static int trusted(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        List<PlayerRecord> records = FriedaiServerState.get(ctx.getSource().getServer())
                .memory()
                .book(sender.getUUID())
                .trusted();
        if (records.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("friedai.command.trusted.none"), false);
            return 0;
        }
        String names = records.stream().map(PlayerRecord::lastName).collect(Collectors.joining(", "));
        ctx.getSource()
                .sendSuccess(() -> Component.translatable("friedai.command.trusted.list", names), false);
        return records.size();
    }

    /** The sender's memory book: trust belongs to the owner, not to a companion entity. */
    private static MemoryBook book(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer sender = source.getPlayerOrException();
        return FriedaiServerState.get(source.getServer()).memory().book(sender.getUUID());
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var state = FriedaiServerState.get(ctx.getSource().getServer());
        ServerConfig loaded = state.store().load();
        List<String> problems = state.applyConfig(loaded);
        problems.forEach(p -> FriedaiMod.LOGGER.warn("Config reload: {}", p));
        ctx.getSource().sendSuccess(
                () -> problems.isEmpty()
                        ? Component.translatable("friedai.command.reloaded")
                        : Component.translatable("friedai.command.reloaded_with_fixes", problems.size()),
                true);
        return 1;
    }
}
