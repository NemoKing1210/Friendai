package dev.friendai.execution.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArgCoercionTest {
    private static final JsonObject SCHEMA = SchemaBuilder.object()
            .string("block", "Block id")
            .integer("count", "How many", 1, 64)
            .bool("collect", "Pick up the drops")
            .required("block", "count")
            .build();

    private static JsonObject args(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void aQuotedIntegerAndFlagBecomeTheirTypes() {
        JsonObject written = args("{\"block\":\"minecraft:dead_bush\",\"count\":\"2\",\"collect\":\"TRUE\"}");

        JsonObject repaired = ArgCoercion.coerce(SCHEMA, written);

        assertTrue(ArgValidator.validate(SCHEMA, repaired).isEmpty(), "the repaired call passes validation");
        assertEquals(2, repaired.get("count").getAsInt());
        assertTrue(repaired.get("count").getAsJsonPrimitive().isNumber());
        assertTrue(repaired.get("collect").getAsJsonPrimitive().isBoolean());
        assertTrue(written.get("count").getAsJsonPrimitive().isString(), "the caller's object is not mutated");
    }

    @Test
    void anAlreadyTypedCallIsReturnedAsIs() {
        JsonObject written = args("{\"block\":\"minecraft:stone\",\"count\":3}");

        assertSame(written, ArgCoercion.coerce(SCHEMA, written));
    }

    @Test
    void textThatIsNotTheValueStaysAnError() {
        JsonObject words = ArgCoercion.coerce(SCHEMA, args("{\"block\":\"x\",\"count\":\"two\"}"));
        JsonObject fraction = ArgCoercion.coerce(SCHEMA, args("{\"block\":\"x\",\"count\":\"2.5\"}"));
        JsonObject flag = ArgCoercion.coerce(SCHEMA, args("{\"block\":\"x\",\"count\":1,\"collect\":\"yes\"}"));

        assertEquals(List.of("'count' must be an integer"), ArgValidator.validate(SCHEMA, words));
        assertEquals(List.of("'count' must be an integer"), ArgValidator.validate(SCHEMA, fraction));
        assertEquals(List.of("'collect' must be a boolean"), ArgValidator.validate(SCHEMA, flag));
    }

    @Test
    void aStringPropertyKeepsItsDigits() {
        JsonObject written = args("{\"block\":\"42\",\"count\":1}");

        JsonObject repaired = ArgCoercion.coerce(SCHEMA, written);

        assertSame(written, repaired);
        assertTrue(repaired.get("block").getAsJsonPrimitive().isString());
    }

    @Test
    void aRepairedValueIsStillRangeChecked() {
        JsonObject repaired = ArgCoercion.coerce(SCHEMA, args("{\"block\":\"x\",\"count\":\"65\"}"));

        assertEquals(List.of("'count' must be at most 64"), ArgValidator.validate(SCHEMA, repaired));
    }
}
