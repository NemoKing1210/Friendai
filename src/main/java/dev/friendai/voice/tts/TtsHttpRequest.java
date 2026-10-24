package dev.friendai.voice.tts;

import java.net.URI;
import java.util.Map;

/** An HTTP exchange to perform. {@link #toString()} prints header names only, never their values. */
public record TtsHttpRequest(String method, URI uri, Map<String, String> headers, byte[] body) {
    public TtsHttpRequest {
        headers = Map.copyOf(headers);
        body = body.clone();
    }

    @Override
    public String toString() {
        return "TtsHttpRequest[method=" + method + ", uri=" + uri
                + ", headers=" + headers.keySet() + ", bodyBytes=" + body.length + "]";
    }
}
