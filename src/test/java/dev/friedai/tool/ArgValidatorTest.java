package dev.friedai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ArgValidatorTest {
    private static final JsonObject SCHEMA = SchemaBuilder.object()
            .string("block", "Block id")
            .integer("count", "How many", 1, 64)
            .enumeration("target", "Where", "owner")
            .required("block")
            .build();

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void requiredMissing() {
        assertEquals(1, ArgValidator.validate(SCHEMA, args("{}")).size());
        assertTrue(ArgValidator.validate(SCHEMA, args("{}")).get(0).contains("block"));
    }

    @Test
    void wrongType() {
        assertEquals(1, ArgValidator.validate(SCHEMA, args("{\"block\":5}")).size());
        assertEquals(1, ArgValidator.validate(SCHEMA, args("{\"block\":\"x\",\"count\":1.5}")).size());
    }

    @Test
    void enumViolation() {
        assertEquals(1, ArgValidator.validate(SCHEMA, args("{\"block\":\"x\",\"target\":\"moon\"}")).size());
        assertTrue(ArgValidator.validate(SCHEMA, args("{\"block\":\"x\",\"target\":\"owner\"}")).isEmpty());
    }

    @Test
    void minMax() {
        assertEquals(1, ArgValidator.validate(SCHEMA, args("{\"block\":\"x\",\"count\":0}")).size());
        assertEquals(1, ArgValidator.validate(SCHEMA, args("{\"block\":\"x\",\"count\":65}")).size());
        assertTrue(ArgValidator.validate(SCHEMA, args("{\"block\":\"x\",\"count\":64}")).isEmpty());
    }

    @Test
    void extraFieldsAreAllowed() {
        assertTrue(ArgValidator.validate(SCHEMA, args("{\"block\":\"x\",\"unknown\":true}")).isEmpty());
    }
}
