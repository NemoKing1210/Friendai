package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlannerToolDigestTest {
    private static PlannerToolDigest.Tool tool(String name, String schema) {
        return new PlannerToolDigest.Tool(
                name,
                "First sentence. Second sentence.",
                JsonParser.parseString(schema).getAsJsonObject());
    }

    private static final PlannerToolDigest.Tool SYNTHETIC = tool("place_beacon", """
            {"type":"object","required":["mode","target"],"properties":{
              "mode":{"type":"string","enum":["fast","careful","silent"]},
              "target":{"type":"object","required":["id","count"],"properties":{
                 "id":{"type":"string"},
                 "count":{"type":"integer","minimum":1,"maximum":64}}},
              "radius":{"type":"integer","minimum":2,"maximum":16}}}""");

    @Test
    void aDigestCarriesTheConstraintsProseCannotInfer() {
        String line = PlannerToolDigest.describe(SYNTHETIC);

        assertTrue(line.contains("mode*: string[fast|careful|silent]"), line);
        assertTrue(line.contains("target*: object{id*: string, count*: integer 1..64}"), line);
        assertTrue(line.contains("radius: integer 2..16"), line);
        assertTrue(line.contains("place_beacon("), line);
        assertTrue(line.contains("First sentence."), line);
        assertTrue(!line.contains("Second sentence."), "the digest keeps one sentence: " + line);
    }

    @Test
    void aDigestIsBoundedAndSaysWhatItLeftOut() {
        List<PlannerToolDigest.Tool> tools = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            tools.add(tool("tool_" + i, "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}"));
        }

        String digest = PlannerToolDigest.build(tools);

        assertTrue(digest.length() <= PlannerToolDigest.MAX_CHARS, "digest length " + digest.length());
        assertTrue(digest.contains("tool_0("), "the first tools are kept");
        assertTrue(digest.contains("more tools are omitted"), digest.substring(digest.length() - 200));
        assertTrue(
                digest.indexOf("tool_399(") < 0 || digest.length() <= PlannerToolDigest.MAX_CHARS,
                "no whole line is cut in half");
        for (String line : digest.lines().toList()) {
            if (line.startsWith("- tool_")) {
                assertTrue(line.endsWith(")") || line.contains("): "), "a kept line is whole: " + line);
            }
        }
    }

    @Test
    void aDigestOfNothingIsEmpty() {
        assertEquals("", PlannerToolDigest.build(List.of()));
    }
}
