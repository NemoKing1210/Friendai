package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MultipartBodyTest {
    @Test
    void multipartBodyContainsFieldAndFile() {
        MultipartBody body = new MultipartBody("XYZ")
                .field("model", "gpt-transcribe")
                .file("file", "audio.wav", "audio/wav", new byte[] {1, 2, 3});

        String text = new String(body.build(), StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("Content-Disposition: form-data; name=\"model\"\r\n\r\ngpt-transcribe"));
        assertTrue(text.contains("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\""));
        assertTrue(text.contains("Content-Type: audio/wav"));
        assertTrue(text.endsWith("--XYZ--\r\n"));
        assertEquals("multipart/form-data; boundary=XYZ", body.contentType());
    }
}
