package dev.friendai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArgValidatorTest {
    private static final JsonObject SCHEMA = SchemaBuilder.object()
            .string("block", "Block id")
            .integer("count", "How many", 1, 64)
            .enumeration("target", "Where", "owner")
            .required("block")
            .build();

    private static final JsonObject PLAN = SchemaBuilder.object()
            .array("steps", "The actions to run, in order", SchemaBuilder.object()
                    .enumeration("tool", "Tool to run", "go_to", "drop_items")
                    .string("args", "JSON object with the tool's arguments")
                    .required("tool")
                    .build(), 1, 3)
            .build();

    private static final JsonObject UUIDS = SchemaBuilder.object()
            .array("ids", "UUID strings", stringSchema(), 1, 3)
            .build();

    private static JsonObject stringSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        return schema;
    }

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

    @Test
    void arrayMustBeArray() {
        assertEquals(List.of("'steps' must be an array"), ArgValidator.validate(PLAN, args("{\"steps\":\"x\"}")));
    }

    @Test
    void arraySizeLimits() {
        assertEquals(List.of("'steps' must have at least 1 items"), ArgValidator.validate(PLAN, args("{\"steps\":[]}")));
        assertEquals(List.of("'steps' must have at most 3 items"),
                ArgValidator.validate(PLAN, args("{\"steps\":[{\"tool\":\"go_to\"},{\"tool\":\"go_to\"},{\"tool\":\"go_to\"},{\"tool\":\"go_to\"}]}")));
    }

    @Test
    void nestedItemErrorsCarryPath() {
        List<String> errors = ArgValidator.validate(PLAN, args("{\"steps\":[{\"tool\":\"go_to\"},{\"tool\":\"fly\"},{\"args\":\"{}\"}]}"));
        assertEquals(List.of("'steps[2].tool' must be one of [go_to, drop_items]", "missing required argument 'steps[3].tool'"), errors);
    }

    @Test
    void nonObjectItemRejected() {
        assertEquals(List.of("'steps[1]' must be an object"), ArgValidator.validate(PLAN, args("{\"steps\":[5]}")));
    }

    @Test
    void primitiveArrayItemsAreValidatedByTheirDeclaredSchema() {
        assertTrue(ArgValidator.validate(UUIDS, args("{\"ids\":[\"one\",\"two\"]}")).isEmpty());
        assertEquals(List.of("'ids[2]' must be a string"),
                ArgValidator.validate(UUIDS, args("{\"ids\":[\"one\",2]}")));
    }

    @Test
    void validPlanPasses() {
        assertTrue(ArgValidator.validate(PLAN, args("{\"steps\":[{\"tool\":\"drop_items\",\"args\":\"{}\"}]}")).isEmpty());
    }
}
