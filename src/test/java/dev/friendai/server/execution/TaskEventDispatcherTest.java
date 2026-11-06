package dev.friendai.server.execution;

import static org.junit.jupiter.api.Assertions.*;

import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskEventDispatcherTest {
    private final List<String> delivered = new ArrayList<>();
    private final TaskEventDispatcher dispatcher = new TaskEventDispatcher();

    @Test
    void anEventEmittedInsideATransitionIsDeliveredOnlyAfterItSettles() {
        dispatcher.sink(sink());

        dispatcher.inTransition(() -> {
            dispatcher.emit(sink -> sink.onDebug("first"));
            assertTrue(delivered.isEmpty(), "a transition must not deliver while it is open");
        });

        assertEquals(List.of("first"), delivered);
    }

    @Test
    void aNestedTransitionFlushesAtItsOutermostEnd() {
        dispatcher.sink(sink());

        dispatcher.inTransition(() -> {
            dispatcher.emit(sink -> sink.onDebug("outer"));
            dispatcher.inTransition(() -> dispatcher.emit(sink -> sink.onDebug("inner")));
            assertEquals(List.of(), delivered, "the inner transition must not flush early");
        });

        assertEquals(List.of("outer", "inner"), delivered);
    }

    @Test
    void anEventOutsideATransitionIsDeliveredImmediately() {
        dispatcher.sink(sink());

        dispatcher.emit(sink -> sink.onDebug("now"));

        assertEquals(List.of("now"), delivered);
    }

    @Test
    void aNoticeRaisedBeforeTheSinkIsReplayedWhenItAttaches() {
        dispatcher.notice("is anyone there");

        assertTrue(delivered.isEmpty(), "nothing can be delivered before the sink exists");

        dispatcher.sink(sink());

        assertEquals(List.of("notice:is anyone there"), delivered);
    }

    private TaskEventSink sink() {
        return new TaskEventSink() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {}

            @Override
            public void onCancelled(int id, String toolName, String reason) {}

            @Override
            public void onNotice(String text) {
                delivered.add("notice:" + text);
            }

            @Override
            public void onDebug(String text) {
                delivered.add(text);
            }
        };
    }
}
