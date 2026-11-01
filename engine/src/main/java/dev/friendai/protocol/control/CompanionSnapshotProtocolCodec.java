package dev.friendai.protocol.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.execution.history.TaskRecord;
import dev.friendai.foundation.diagnostics.LogEntry;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolDataException;
import dev.friendai.protocol.ProtocolEnumAdapter;
import dev.friendai.protocol.ProtocolFamily;
import dev.friendai.protocol.ProtocolJsonViewCodec;
import dev.friendai.protocol.ProtocolVersion;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.ProtocolViewCodec;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/** Bounded control snapshot codec with final operator-field projection. */
public final class CompanionSnapshotProtocolCodec {
    public static final int MAX_FRAME_BYTES = 1 << 20;
    private static final int MAX_LIST_COUNT = 1_000;
    private static final int MAX_STRING_BYTES = 32_767;
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(
                    LogKind.class,
                    new ProtocolEnumAdapter<>(
                                    Map.of(
                                            LogKind.USER,
                                            1,
                                            LogKind.ASSISTANT,
                                            2,
                                            LogKind.TOOL_CALL,
                                            3,
                                            LogKind.TOOL_RESULT,
                                            4,
                                            LogKind.ERROR,
                                            5,
                                            LogKind.TASK,
                                            6,
                                            LogKind.MANUAL,
                                            7,
                                            LogKind.PLANNER,
                                            8),
                                    id -> switch (id) {
                                        case 1 -> LogKind.USER;
                                        case 2 -> LogKind.ASSISTANT;
                                        case 3 -> LogKind.TOOL_CALL;
                                        case 4 -> LogKind.TOOL_RESULT;
                                        case 5 -> LogKind.ERROR;
                                        case 6 -> LogKind.TASK;
                                        case 7 -> LogKind.MANUAL;
                                        case 8 -> LogKind.PLANNER;
                                        default -> null;
                                    })
                            .nullSafe())
            .create();
    private static final Type TYPE = CompanionSnapshotView.class;

    private CompanionSnapshotProtocolCodec() {}

    public static byte[] encodeForRecipient(CompanionSnapshotView snapshot, boolean operatorRecipient, long requestId) {
        CompanionSnapshotView projected = project(snapshot, operatorRecipient);
        validate(projected);
        byte[] body =
                ProtocolJsonViewCodec.encodeBody(projected, TYPE, GSON, MAX_FRAME_BYTES - ProtocolVersion.HEADER_BYTES);
        return ProtocolViewCodec.encode(ProtocolFamily.CONTROL, 128, requestId, body);
    }

    public static DecodeResult<ProtocolView<CompanionSnapshotView>> decode(byte[] encoded) {
        return ProtocolJsonViewCodec.decode(
                encoded, ProtocolFamily.CONTROL, 128, TYPE, GSON, CompanionSnapshotProtocolCodec::validate);
    }

    private static CompanionSnapshotView project(CompanionSnapshotView snapshot, boolean operatorRecipient) {
        if (snapshot == null) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        return new CompanionSnapshotView(
                snapshot.present(),
                operatorRecipient,
                snapshot.companionName(),
                snapshot.provider(),
                snapshot.model(),
                snapshot.thinking(),
                snapshot.currentTask(),
                snapshot.history(),
                operatorRecipient ? snapshot.newLog() : List.of(),
                operatorRecipient ? snapshot.perception() : "",
                operatorRecipient ? snapshot.toolNames() : List.of(),
                snapshot.vitals(),
                snapshot.queue(),
                snapshot.autoRespawn(),
                snapshot.autoRespawnAvailable(),
                snapshot.creativeMode(),
                snapshot.creativeModeAvailable(),
                snapshot.freeTimeOn(),
                snapshot.freeTimeActivity(),
                snapshot.freeTimeWaiting(),
                snapshot.followMode(),
                snapshot.freeTimeNext(),
                snapshot.freeTimeBudget(),
                snapshot.freeTimeLast(),
                snapshot.freeTimePlannerModel(),
                snapshot.bondTier(),
                snapshot.bondReasons(),
                snapshot.goalText(),
                snapshot.goalCriteria(),
                snapshot.goalUnmet());
    }

    private static void validate(CompanionSnapshotView snapshot) {
        if (snapshot.history() == null
                || snapshot.newLog() == null
                || snapshot.toolNames() == null
                || snapshot.queue() == null
                || snapshot.vitals() == null
                || snapshot.bondReasons() == null
                || snapshot.goalCriteria() == null
                || snapshot.goalUnmet() == null
                || snapshot.history().size() > MAX_LIST_COUNT
                || snapshot.newLog().size() > MAX_LIST_COUNT
                || snapshot.toolNames().size() > MAX_LIST_COUNT
                || snapshot.queue().size() > MAX_LIST_COUNT
                || snapshot.bondReasons().size() > MAX_LIST_COUNT
                || snapshot.goalCriteria().size() > MAX_LIST_COUNT
                || snapshot.goalUnmet().size() > MAX_LIST_COUNT
                || (!snapshot.operator()
                        && (!snapshot.newLog().isEmpty()
                                || !snapshot.perception().isEmpty()
                                || !snapshot.toolNames().isEmpty()))) {
            throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
        }
        text(snapshot.companionName(), 512);
        text(snapshot.provider(), 512);
        text(snapshot.model(), 512);
        text(snapshot.currentTask(), 2_000);
        for (TaskRecord row : snapshot.history()) {
            if (row == null) throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
            text(row.toolName(), 512);
            text(row.status(), 128);
            text(row.summary(), 2_000);
        }
        for (LogEntry entry : snapshot.newLog()) {
            if (entry == null || entry.kind() == null)
                throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
            text(entry.text(), 4_000);
        }
        text(snapshot.perception(), MAX_STRING_BYTES);
        for (String name : snapshot.toolNames()) text(name, 512);
        CompanionSnapshotView.Vitals vitals = snapshot.vitals();
        text(vitals.protection(), 128);
        text(vitals.style(), 128);
        text(vitals.action(), 128);
        text(vitals.reason(), 512);
        text(vitals.paceMode(), 128);
        for (CompanionSnapshotView.QueueItem row : snapshot.queue()) {
            if (row == null || row.after() == null || row.after().size() > MAX_LIST_COUNT)
                throw new ProtocolDataException(DecodeResult.Reason.INVALID_FIELD);
            text(row.planGoal(), 2_000);
            text(row.planStatus(), 128);
            text(row.tool(), 512);
            text(row.args(), 8_192);
            text(row.state(), 128);
            text(row.note(), 2_000);
        }
        text(snapshot.freeTimeActivity(), 800);
        text(snapshot.freeTimeNext(), 640);
        text(snapshot.freeTimeBudget(), 320);
        text(snapshot.freeTimeLast(), 800);
        text(snapshot.freeTimePlannerModel(), 512);
        text(snapshot.bondTier(), 96);
        for (String reason : snapshot.bondReasons()) text(reason, 640);
        text(snapshot.goalText(), 640);
        for (String criterion : snapshot.goalCriteria()) text(criterion, 640);
        for (String criterion : snapshot.goalUnmet()) text(criterion, 640);
    }

    private static void text(String value, int maxBytes) {
        ProtocolJsonViewCodec.requireUtf8(value, maxBytes);
    }
}
