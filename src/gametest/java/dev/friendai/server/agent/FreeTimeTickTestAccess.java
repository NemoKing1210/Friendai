package dev.friendai.server.agent;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.ToolResult;
import java.util.List;

/** Drives the existing server decision tick with a deterministic clock in GameTests. */
public final class FreeTimeTickTestAccess {
    private FreeTimeTickTestAccess() {}

    public static void tick(FreeTimeDriver driver, CompanionEntity companion, long now) {
        driver.tick(companion.ownerUuid(), companion, false, false, now);
    }

    public static ToolResult choose(FreeTimeDriver driver, CompanionEntity companion, long now) {
        return driver.chooseAt(companion, "test late choice", List.of(), "", 5, "EXPLORE", now);
    }
}
