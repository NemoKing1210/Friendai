package dev.friendai.features.speech;

import java.util.List;
import java.util.Set;

/**
 * One selectable speech provider as data. {@code protocol} names the wire adapter;
 * {@code local} is the protocol of the built-in offline engine.
 */
public record VoicePreset(
        String id,
        String displayName,
        String protocol,
        String defaultBaseUrl,
        VoiceAuth auth,
        String defaultModel,
        String defaultVoice,
        String defaultLanguage,
        String defaultApiKey,
        boolean paid,
        String docsUrl,
        List<String> voices,
        Set<CloudField> fields) {

    public VoicePreset {
        voices = voices == null ? List.of() : List.copyOf(voices);
        fields = fields == null ? Set.of() : Set.copyOf(fields);
    }

    public boolean uses(CloudField field) {
        return fields.contains(field);
    }

    public boolean local() {
        return "local".equals(protocol);
    }
}
