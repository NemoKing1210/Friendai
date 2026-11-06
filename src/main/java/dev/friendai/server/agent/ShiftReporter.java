package dev.friendai.server.agent;

import dev.friendai.agent.autonomy.AwaySummary;
import dev.friendai.agent.autonomy.FreeTimeLogEvents;
import dev.friendai.agent.autonomy.FreeTimeService;
import dev.friendai.agent.autonomy.FreeTimeText;
import dev.friendai.agent.autonomy.Shift;
import dev.friendai.agent.autonomy.ShiftStopWhen;
import dev.friendai.agent.turn.CompanionSpeechPolicy;
import dev.friendai.common.Components;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.server.companion.CompanionNames;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Closes and reports one free-time shift: the end is saved once, written to the durable log and, when it is news,
 * told to the owner (or kept as an away line when they are not there).
 */
final class ShiftReporter {
    private final MinecraftServer server;
    private final FreeTimeService service;
    private final CompanionSessions brains;

    ShiftReporter(MinecraftServer server, FreeTimeService service, CompanionSessions brains) {
        this.server = server;
        this.service = service;
        this.brains = brains;
    }

    /**
     * Closes a shift once: saves the end, writes the durable record and, when asked, tells the owner. Every path that
     * ends a shift goes through here, so no shift is left with a start and no end in the log.
     */
    void finish(
            UUID owner,
            CompanionEntity companion,
            Shift shift,
            boolean success,
            String summary,
            ShiftStopWhen stopWhen,
            long nowMillis,
            boolean report) {
        var diagnosis = success || summary == null || summary.isBlank()
                ? null
                : new dev.friendai.agent.recover.RecoveryAdvisor().diagnose("", summary, Set.of());
        service.finishShift(owner, success, summary, nowMillis, stopWhen);
        brains.logFreeTime(FreeTimeLogEvents.end(
                owner,
                companion.getUUID(),
                brains.logSession(owner),
                shift == null ? 0L : shift.planId(),
                success,
                stopWhen.name(),
                summary,
                diagnosis == null ? "" : diagnosis.kind().name(),
                diagnosis == null ? "" : diagnosis.detail()));
        if (report) {
            reportEnd(owner, companion, shift, success, summary, stopWhen);
        }
    }

    /** One short translated line when a shift ends; an absent owner hears it when they come back. */
    private void reportEnd(
            UUID owner,
            CompanionEntity companion,
            Shift shift,
            boolean success,
            String summary,
            ShiftStopWhen stopWhen) {
        if (shift == null) {
            return;
        }
        if (!success
                && CompanionSpeechPolicy.shouldReportShiftFailure(
                        shift, summary, stopWhen, service.state(owner).history())) {
            UserMessage line = FreeTimeText.shiftFailureMessage(summary);
            brains.logFreeTime(FreeTimeLogEvents.report(
                    owner, companion.getUUID(), brains.logSession(owner), shift.planId(), line.key()));
            tell(owner, companion, line);
        }
    }

    /** A fixed line to the owner when they can hear it, otherwise saved for when they come back. */
    void tell(UUID owner, CompanionEntity companion, UserMessage message) {
        var player = companion.owner();
        if (player.isPresent()) {
            noteCompanionSpeech(owner);
            player.get()
                    .sendSystemMessage(Component.literal("<")
                            .append(CompanionNames.styled(companion.getName().getString()))
                            .append(Component.literal("> "))
                            .append(Components.of(message)));
            brains.logDirectDelivery(owner, message.key());
        } else {
            service.update(owner, s -> AwaySummary.addMessage(s, message));
        }
    }

    /** A direct line is speech too: the social director must know the companion just talked. */
    void noteCompanionSpeech(UUID owner) {
        ServerRuntimeRegistry.find(server)
                .ifPresent(runtime -> runtime.socialDirector()
                        .companionSpoke(owner, runtime.server().overworld().getGameTime()));
    }
}
