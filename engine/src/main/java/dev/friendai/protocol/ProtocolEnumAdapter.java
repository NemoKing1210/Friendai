package dev.friendai.protocol;

import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

/** Numeric JSON adapter for enums whose protocol IDs must remain explicit and stable. */
public final class ProtocolEnumAdapter<E> extends TypeAdapter<E> {
    private final Map<E, Integer> ids;
    private final Function<Integer, E> values;

    public ProtocolEnumAdapter(Map<E, Integer> ids, Function<Integer, E> values) {
        this.ids = Map.copyOf(ids);
        this.values = values;
    }

    @Override
    public void write(JsonWriter out, E value) throws IOException {
        Integer id = ids.get(value);
        if (id == null) throw new JsonParseException("unknown protocol enum value");
        out.value(id);
    }

    @Override
    public E read(JsonReader in) throws IOException {
        if (in.peek() != JsonToken.NUMBER) throw new JsonParseException("protocol enum ID must be an integer");
        int id;
        try {
            id = Integer.parseInt(in.nextString());
        } catch (NumberFormatException malformed) {
            throw new JsonParseException("protocol enum ID must be an integer", malformed);
        }
        E value = values.apply(id);
        if (value == null) throw new JsonParseException("unknown protocol enum id");
        return value;
    }
}
