package dev.friendai.foundation.diagnostics;

import java.util.List;

/**
 * The closed set of machine-readable event names. Scripts, digests and the model rely on these tokens, so a new
 * kind of record adds a constant here and a row to the log README in the same change.
 */
public final class LogEvents {
    public static final String TURN_START = "turn.start";
    public static final String TURN_END = "turn.end";
    public static final String EVENT_DROPPED = "event.dropped";
    public static final String EVENT_POSTED = "event.posted";
    public static final String ASSISTANT_TEXT = "assistant.text";
    public static final String ASSISTANT_DELIVERED = "assistant.delivered";
    public static final String ASSISTANT_DIRECT = "assistant.direct";
    public static final String TOOL_CALL = "tool.call";
    public static final String TOOL_RESULT = "tool.result";
    public static final String SKILL_SELECT = "skill.select";
    public static final String LLM_REQUEST = "llm.request";
    public static final String LLM_RESPONSE = "llm.response";
    public static final String LLM_USAGE = "llm.usage";
    public static final String LLM_ERROR = "llm.error";
    public static final String TASK_START = "task.start";
    public static final String TASK_PROGRESS = "task.progress";
    public static final String TASK_HOLD = "task.hold";
    public static final String TASK_RESUME = "task.resume";
    public static final String TASK_FINISH = "task.finish";
    public static final String TASK_CANCEL = "task.cancel";
    public static final String TASK_RESTORE = "task.restore";
    public static final String TASK_REJECT = "task.reject";
    public static final String PLAN_START = "plan.start";
    public static final String PLAN_STEP = "plan.step";
    public static final String PLAN_FINISH = "plan.finish";
    public static final String QUEUE_ADD = "queue.add";
    public static final String QUEUE_ACTIVATE = "queue.activate";
    public static final String QUEUE_BLOCK = "queue.block";
    public static final String SHIFT_START = "shift.start";
    public static final String SHIFT_END = "shift.end";
    public static final String SHIFT_REPORT = "shift.report";
    public static final String CONFIG_LOAD = "config.load";
    public static final String CONFIG_SAVE = "config.save";
    public static final String CONFIG_MIGRATE = "config.migrate";
    public static final String CONFIG_ERROR = "config.error";
    public static final String SERVER_START = "server.start";
    public static final String SERVER_STOP = "server.stop";
    public static final String SERVER_WARN = "server.warn";
    public static final String SERVER_ERROR = "server.error";
    public static final String CLIENT_ERROR = "client.error";
    public static final String LOG_DROPPED = "log.dropped";

    private static final List<String> ALL = List.of(
            TURN_START,
            TURN_END,
            EVENT_DROPPED,
            ASSISTANT_TEXT,
            ASSISTANT_DELIVERED,
            TOOL_CALL,
            TOOL_RESULT,
            SKILL_SELECT,
            LLM_REQUEST,
            LLM_RESPONSE,
            LLM_USAGE,
            LLM_ERROR,
            TASK_START,
            TASK_PROGRESS,
            TASK_HOLD,
            TASK_RESUME,
            TASK_FINISH,
            TASK_CANCEL,
            TASK_RESTORE,
            TASK_REJECT,
            PLAN_START,
            PLAN_STEP,
            PLAN_FINISH,
            QUEUE_ADD,
            QUEUE_ACTIVATE,
            QUEUE_BLOCK,
            CONFIG_LOAD,
            CONFIG_SAVE,
            CONFIG_MIGRATE,
            CONFIG_ERROR,
            SERVER_START,
            SERVER_STOP,
            SERVER_WARN,
            SERVER_ERROR,
            CLIENT_ERROR,
            LOG_DROPPED);

    private LogEvents() {}

    /** Every known event token, in declaration order. */
    public static List<String> all() {
        return ALL;
    }
}
