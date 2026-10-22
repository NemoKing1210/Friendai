package dev.friedai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class SecretRedactorTest {
    @Test
    void masksEveryOccurrence() {
        UnaryOperator<String> r = SecretRedactor.of(() -> List.of("sk-abc"));
        assertEquals("a *** b *** c", r.apply("a sk-abc b sk-abc c"));
    }

    @Test
    void ignoresBlankSecrets() {
        UnaryOperator<String> r = SecretRedactor.of(() -> List.of("", "  "));
        assertEquals("keep me", r.apply("keep me"));
    }

    @Test
    void readsSecretsLazily() {
        List<String> secrets = new ArrayList<>();
        UnaryOperator<String> r = SecretRedactor.of(() -> secrets);
        assertEquals("sk-1", r.apply("sk-1"));
        secrets.add("sk-1");
        assertEquals("***", r.apply("sk-1"));
    }
}
