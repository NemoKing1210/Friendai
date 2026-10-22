package dev.friedai.net;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class Operators {
    private Operators() {}

    /**
     * Whether the player may use the debugging parts of the companion menu: a gamemaster, or the owner of a
     * single-player (or LAN) world, who is an operator in spirit even with cheats off.
     */
    public static boolean isOperator(ServerPlayer player) {
        return Commands.LEVEL_GAMEMASTERS.check(player.permissions())
                || player.level().getServer().isSingleplayerOwner(player.nameAndId());
    }
}
