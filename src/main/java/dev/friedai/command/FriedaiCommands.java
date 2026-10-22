package dev.friedai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.CompanionManager;
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
                .then(Commands.literal("dismiss").executes(FriedaiCommands::dismiss)));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        CompanionEntity companion = CompanionManager.get(ctx.getSource().getServer()).summon(player);
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
}
