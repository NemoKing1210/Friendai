package dev.friendai.command;

import dev.friendai.creative.CreativeCommandPolicy;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.net.Operators;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;

/** Executes one validated command with the online owner's permissions and the companion's location/entity source. */
public final class CompanionCommandExecutor {
    public record Result(boolean success, String text) {}

    private CompanionCommandExecutor() {}

    public static Result execute(MinecraftServer server, CompanionEntity companion, ServerPlayer owner, String raw) {
        if (server == null
                || companion == null
                || owner == null
                || owner.level().getServer() != server
                || companion.level().getServer() != server
                || companion.ownerUuid() == null
                || !companion.ownerUuid().equals(owner.getUUID())
                || server.getPlayerList().getPlayer(owner.getUUID()) != owner) {
            return new Result(false, "the owner must be online in this server");
        }
        if (!Operators.isOperator(owner) || !Commands.LEVEL_GAMEMASTERS.check(owner.permissions())) {
            return new Result(false, "the owner does not have permission to run commands");
        }
        CreativeCommandPolicy.Decision decision =
                CreativeCommandPolicy.evaluate(raw, owner.getName().getString());
        if (!decision.allowed()) return new Result(false, decision.reason());

        BoundedOutput output = new BoundedOutput();
        boolean[] succeeded = {false};
        CommandSourceStack source = owner.createCommandSourceStack()
                .withSource(output)
                .withLevel((ServerLevel) companion.level())
                .withPosition(companion.position())
                .withRotation(new Vec2(companion.getXRot(), companion.getYRot()))
                .withEntity(companion)
                .withCallback((success, result) -> succeeded[0] = success);
        try {
            server.getCommands().getDispatcher().execute(decision.command(), source);
        } catch (Exception e) {
            String feedback = output.text();
            String reason = e.getMessage();
            return new Result(false, feedback.isBlank() ? boundedError(reason) : feedback);
        }
        String feedback = output.text();
        if (!succeeded[0]) {
            return new Result(false, feedback.isBlank() ? "command did not report success" : feedback);
        }
        return new Result(true, feedback.isBlank() ? "command succeeded" : feedback);
    }

    private static String boundedError(String message) {
        if (message == null || message.isBlank()) return "command syntax or execution failed";
        return message.length() <= CreativeCommandPolicy.maxFeedbackChars()
                ? message
                : message.substring(0, CreativeCommandPolicy.maxFeedbackChars() - 1) + "…";
    }

    private static final class BoundedOutput implements CommandSource {
        private final List<String> lines = new ArrayList<>();
        private int chars;
        private boolean truncated;

        @Override
        public void sendSystemMessage(Component message) {
            if (truncated || message == null) return;
            String text = message.getString();
            for (String line : text.split("\\R", -1)) {
                if (lines.size() >= CreativeCommandPolicy.maxFeedbackLines()
                        || chars >= CreativeCommandPolicy.maxFeedbackChars()) {
                    truncated = true;
                    break;
                }
                int remaining = CreativeCommandPolicy.maxFeedbackChars() - chars;
                String bounded =
                        line.length() <= remaining ? line : line.substring(0, Math.max(0, remaining - 1)) + "…";
                lines.add(bounded);
                chars += bounded.length();
                if (bounded.length() < line.length()) {
                    truncated = true;
                    break;
                }
            }
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        private String text() {
            return String.join("\n", lines);
        }
    }
}
