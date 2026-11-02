package dev.friendai.agent.llm.stream;

/** One server-sent event: its optional type and the joined data lines. */
public record StreamEvent(String event, String data) {
    public StreamEvent {
        event = event == null ? "" : event;
        data = data == null ? "" : data;
    }
}
