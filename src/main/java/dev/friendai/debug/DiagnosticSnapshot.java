package dev.friendai.debug;

import dev.friendai.foundation.diagnostics.LogHealth;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The immutable half of a diagnosis: who it is about, where that owner's files live, the runtime facts already
 * gathered on the server thread, and the writer health at capture time. The file scan and the report render happen
 * later, on the diagnostic worker.
 */
public record DiagnosticSnapshot(
        String subject,
        UUID owner,
        Path ownerLogRoot,
        String boot,
        String mod,
        String minecraft,
        String level,
        List<String> categories,
        Map<String, String> runtimeFacts,
        LogHealth health) {
    public DiagnosticSnapshot {
        subject = subject == null ? "" : subject;
        boot = boot == null ? "" : boot;
        mod = mod == null ? "" : mod;
        minecraft = minecraft == null ? "" : minecraft;
        level = level == null ? "" : level;
        categories = categories == null ? List.of() : List.copyOf(categories);
        runtimeFacts = runtimeFacts == null ? Map.of() : Map.copyOf(runtimeFacts);
    }

    public DiagnosticSnapshot withHealth(LogHealth health) {
        return new DiagnosticSnapshot(
                subject, owner, ownerLogRoot, boot, mod, minecraft, level, categories, runtimeFacts, health);
    }
}
