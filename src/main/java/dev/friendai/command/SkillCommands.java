package dev.friendai.command;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.friendai.core.*;
import dev.friendai.features.instructions.*;
import dev.friendai.net.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Dedicated-server operations use exactly the same validation as the owner menu. */
final class SkillCommands {
    private SkillCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("friendai").then(skills(false)).then(description()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> skills(boolean global) {
        var node = Commands.literal(global ? "server" : "skills");
        if (global) node.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        node.then(Commands.literal("list").executes(ctx -> {
            var p = ctx.getSource().getPlayerOrException();
            var s = FriendaiServerState.get(p.level().getServer()).skills();
            String text = (global ? s.globalListed() : s.listed(p.getUUID()))
                    .stream()
                            .map(v -> v.id() + " v" + v.version() + " " + v.name())
                            .collect(java.util.stream.Collectors.joining("\n"));
            p.sendSystemMessage(Component.translatable("friendai.command.skills.result", text));
            return 1;
        }));
        node.then(Commands.literal("show")
                .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                    var p = ctx.getSource().getPlayerOrException();
                    var service = FriendaiServerState.get(p.level().getServer()).skills();
                    String id = StringArgumentType.getString(ctx, "id");
                    var found = (global ? service.globalListed() : service.listed(p.getUUID()))
                            .stream().filter(v -> v.id().equals(id)).findFirst();
                    p.sendSystemMessage(
                            found.isPresent()
                                    ? Component.translatable(
                                            "friendai.command.skills.result", SkillTransfer.write(found.get()))
                                    : Component.translatable("friendai.menu.skills.invalid"));
                    return found.isPresent() ? 1 : 0;
                })));
        for (String op : new String[] {
            "create", "edit", "duplicate", "delete", "enable", "disable", "approve", "reject", "import", "export"
        }) {
            node.then(Commands.literal(op)
                    .then(Commands.argument("json", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                var p = ctx.getSource().getPlayerOrException();
                                String json = StringArgumentType.getString(ctx, "json");
                                try {
                                    if (json.length() > 32768) throw new IllegalArgumentException();
                                    var object = JsonParser.parseString(json).getAsJsonObject();
                                    object.addProperty("server", global);
                                    var result = SkillRequestHandler.apply(p, op, object.toString());
                                    p.sendSystemMessage(
                                            Components.of(result.status().message()));
                                    if (!result.text().isEmpty())
                                        p.sendSystemMessage(Component.translatable(
                                                "friendai.command.skills.result", result.text()));
                                    return result.status().ok() ? 1 : 0;
                                } catch (RuntimeException | StackOverflowError e) {
                                    p.sendSystemMessage(Component.translatable("friendai.menu.skills.invalid"));
                                    return 0;
                                }
                            })));
        }
        if (!global) node.then(skills(true));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> description() {
        var node = Commands.literal("description");
        node.then(Commands.literal("show").executes(ctx -> {
            var p = ctx.getSource().getPlayerOrException();
            var profile =
                    FriendaiServerState.get(p.level().getServer()).profiles().profile(p.getUUID());
            p.sendSystemMessage(
                    Component.translatable("friendai.command.skills.result", new Gson().toJson(profile.description())));
            return 1;
        }));
        for (String op : new String[] {"set", "language", "address"}) {
            node.then(Commands.literal(op)
                    .then(Commands.argument("value", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                var p = ctx.getSource().getPlayerOrException();
                                String value = StringArgumentType.getString(ctx, "value");
                                var profile = FriendaiServerState.get(p.level().getServer())
                                        .profiles()
                                        .profile(p.getUUID());
                                JsonObject o = new JsonObject();
                                o.addProperty("summary", profile.description().summary());
                                o.addProperty("details", profile.description().details());
                                o.addProperty("language", profile.ownerProfile().language());
                                o.addProperty("address", profile.ownerProfile().address());
                                try {
                                    if (value.length() > 32768) throw new IllegalArgumentException();
                                    if (op.equals("set")) {
                                        var input =
                                                JsonParser.parseString(value).getAsJsonObject();
                                        o.add("summary", input.get("summary"));
                                        o.add("details", input.get("details"));
                                    } else o.addProperty(op, value);
                                    return descriptionResult(p, o);
                                } catch (RuntimeException | StackOverflowError e) {
                                    p.sendSystemMessage(Component.translatable("friendai.menu.skills.invalid"));
                                    return 0;
                                }
                            })));
        }
        node.then(Commands.literal("clear").executes(ctx -> {
            var p = ctx.getSource().getPlayerOrException();
            var profile =
                    FriendaiServerState.get(p.level().getServer()).profiles().profile(p.getUUID());
            JsonObject o = new JsonObject();
            o.addProperty("summary", "");
            o.addProperty("details", "");
            o.addProperty("language", profile.ownerProfile().language());
            o.addProperty("address", profile.ownerProfile().address());
            return descriptionResult(p, o);
        }));
        return node;
    }

    private static int descriptionResult(ServerPlayer p, JsonObject o) {
        var result = SkillRequestHandler.apply(p, "description", o.toString());
        p.sendSystemMessage(Components.of(result.status().message()));
        return result.status().ok() ? 1 : 0;
    }
}
