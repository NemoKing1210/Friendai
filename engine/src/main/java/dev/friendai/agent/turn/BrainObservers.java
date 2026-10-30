package dev.friendai.agent.turn;

import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import java.util.List;

/** Fans one brain event out to several observers; one failing observer never hides the event from the rest. */
public final class BrainObservers implements BrainObserver {
    private final List<BrainObserver> observers;

    private BrainObservers(List<BrainObserver> observers) {
        this.observers = List.copyOf(observers);
    }

    public static BrainObserver of(BrainObserver... observers) {
        List<BrainObserver> list = new java.util.ArrayList<>();
        for (BrainObserver observer : observers) {
            if (observer != null) {
                list.add(observer);
            }
        }
        return list.isEmpty() ? BrainObserver.NONE : new BrainObservers(list);
    }

    @Override
    public void onEvent(BrainEvent e) {
        each(o -> o.onEvent(e));
    }

    @Override
    public void onEventDropped(BrainEvent e, String reason) {
        each(o -> o.onEventDropped(e, reason));
    }

    @Override
    public void onAssistantText(String text) {
        each(o -> o.onAssistantText(text));
    }

    @Override
    public void onReply(Caller caller, String text) {
        each(o -> o.onReply(caller, text));
    }

    @Override
    public void onToolCall(ToolCall call) {
        each(o -> o.onToolCall(call));
    }

    @Override
    public void onToolResult(ToolCall call, ToolResult result) {
        each(o -> o.onToolResult(call, result));
    }

    @Override
    public void onInstructions(List<String> ids, int chars, int promptChars, boolean overrun) {
        each(o -> o.onInstructions(ids, chars, promptChars, overrun));
    }

    @Override
    public void onUsage(Usage usage) {
        each(o -> o.onUsage(usage));
    }

    @Override
    public void onError(Throwable error) {
        each(o -> o.onError(error));
    }

    private void each(java.util.function.Consumer<BrainObserver> call) {
        for (BrainObserver observer : observers) {
            try {
                call.accept(observer);
            } catch (RuntimeException ignored) {
                // A passive observer must never break the brain.
            }
        }
    }
}
