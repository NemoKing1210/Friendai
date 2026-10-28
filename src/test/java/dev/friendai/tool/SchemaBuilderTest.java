package dev.friendai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class SchemaBuilderTest {
    @Test
    void unboundedInteger() {
        JsonObject p = SchemaBuilder.object()
                .integer("x", "X")
                .build()
                .getAsJsonObject("properties")
                .getAsJsonObject("x");
        assertEquals("integer", p.get("type").getAsString());
        assertFalse(p.has("minimum"));
        assertFalse(p.has("maximum"));
    }

    @Test
    void boolShape() {
        JsonObject p = SchemaBuilder.object()
                .bool("wait", "Wait for it")
                .build()
                .getAsJsonObject("properties")
                .getAsJsonObject("wait");
        assertEquals("boolean", p.get("type").getAsString());
        assertEquals("Wait for it", p.get("description").getAsString());
    }

    @Test
    void arrayShape() {
        JsonObject item =
                SchemaBuilder.object().string("tool", "T").required("tool").build();
        JsonObject p = SchemaBuilder.object()
                .array("steps", "S", item, 1, 16)
                .build()
                .getAsJsonObject("properties")
                .getAsJsonObject("steps");
        assertEquals("array", p.get("type").getAsString());
        assertEquals("S", p.get("description").getAsString());
        assertEquals(item, p.getAsJsonObject("items"));
        assertEquals(1, p.get("minItems").getAsInt());
        assertEquals(16, p.get("maxItems").getAsInt());
    }
}
