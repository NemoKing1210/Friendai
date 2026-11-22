package dev.friendai.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.friendai.server.agent.coordination.ServerCoordination;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;

/** Explicit owner decisions for incoming companion coordination requests. */
final class CoordinationCommand {
    private CoordinationCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("coordination")
                .executes(ctx -> coordination(ctx).showIncoming(ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("accept")
                        .then(Commands.argument("id", UuidArgument.uuid()).executes(ctx -> answer(ctx, true))))
                .then(Commands.literal("decline")
                        .then(Commands.argument("id", UuidArgument.uuid()).executes(ctx -> answer(ctx, false))));
    }

    private static int answer(CommandContext<CommandSourceStack> ctx, boolean accept) throws CommandSyntaxException {
        var recipient = ctx.getSource().getPlayerOrException();
        return coordination(ctx).answer(recipient, UuidArgument.getUuid(ctx, "id"), accept) ? 1 : 0;
    }

    private static ServerCoordination coordination(CommandContext<CommandSourceStack> ctx) {
        return ServerRuntimeRegistry.getOrCreate(ctx.getSource().getServer()).coordination();
    }
}
