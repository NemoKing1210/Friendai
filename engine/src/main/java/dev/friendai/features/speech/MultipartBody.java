package dev.friendai.features.speech;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Builds a {@code multipart/form-data} body with a fixed boundary. */
public final class MultipartBody {
    private final String boundary;
    private final List<byte[]> parts = new ArrayList<>();

    public MultipartBody(String boundary) {
        this.boundary = boundary;
    }

    public MultipartBody field(String name, String value) {
        ByteArrayOutputStream part = new ByteArrayOutputStream();
        part.writeBytes(header("Content-Disposition: form-data; name=\"" + name + "\"", null));
        part.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        parts.add(part.toByteArray());
        return this;
    }

    public MultipartBody file(String name, String filename, String contentType, byte[] data) {
        ByteArrayOutputStream part = new ByteArrayOutputStream();
        part.writeBytes(header(
                "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"", contentType));
        part.writeBytes(data);
        parts.add(part.toByteArray());
        return this;
    }

    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(part);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    private static byte[] header(String disposition, String contentType) {
        StringBuilder text = new StringBuilder(disposition).append("\r\n");
        if (contentType != null) {
            text.append("Content-Type: ").append(contentType).append("\r\n");
        }
        text.append("\r\n");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
