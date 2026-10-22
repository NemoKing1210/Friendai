package dev.friedai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.friedai.FriedaiMod;
import dev.friedai.config.ServerConfig;
import dev.friedai.core.Components;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
import dev.friedai.task.Task;
import dev.friedai.text.UserMessage;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
