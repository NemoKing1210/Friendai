package dev.friendai.execution.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Builds the JSON-schema subset used for tool parameters. */
public final class SchemaBuilder {
    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    private SchemaBuilder() {}

    public static SchemaBuilder object() {
        return new SchemaBuilder();
    }

    private JsonObject property(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        properties.add(name, p);
        return p;
    }

    public SchemaBuilder string(String name, String description) {
        property(name, "string", description);
        return this;
    }

    public SchemaBuilder integer(String name, String description, int min, int max) {
        JsonObject p = property(name, "integer", description);
        p.addProperty("minimum", min);
        p.addProperty("maximum", max);
        return this;
    }

    /** An integer with no bounds: for values whose limits are not known here (block coordinates, for example). */
    public SchemaBuilder integer(String name, String description) {
        property(name, "integer", description);
        return this;
    }

    public SchemaBuilder array(String name, String description, JsonObject itemSchema, int minItems, int maxItems) {
        JsonObject p = property(name, "array", description);
        p.add("items", itemSchema);
        p.addProperty("minItems", minItems);
        p.addProperty("maxItems", maxItems);
        return this;
    }

    public SchemaBuilder bool(String name, String description) {
        property(name, "boolean", description);
        return this;
    }

    public SchemaBuilder number(String name, String description) {
        property(name, "number", description);
        return this;
    }

    public SchemaBuilder enumeration(String name, String description, String... values) {
        JsonObject p = property(name, "string", description);
        JsonArray allowed = new JsonArray();
        for (String v : values) {
            allowed.add(v);
        }
        p.add("enum", allowed);
        return this;
    }

    public SchemaBuilder required(String... names) {
        for (String n : names) {
            required.add(n);
        }
        return this;
    }

    public JsonObject build() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (!required.isEmpty()) {
            schema.add("required", required);
        }
        return schema;
    }
}
