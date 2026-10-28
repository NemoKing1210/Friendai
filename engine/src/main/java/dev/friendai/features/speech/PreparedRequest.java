package dev.friendai.features.speech;

import java.net.URI;
import java.util.Map;

/** A fully built HTTP request, so a protocol adapter needs no knowledge of the transport. */
public record PreparedRequest(URI uri, Map<String, String> headers, byte[] body, String contentType) {
    public PreparedRequest {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    /** Header values may carry the API key, so they never appear here. */
    @Override
    public String toString() {
        return "PreparedRequest[uri=" + uri + ", headers=" + headers.size() + ", contentType=" + contentType + ", body="
                + body.length + " bytes]";
    }
}
