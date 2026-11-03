package dev.friendai.features.storage;

import java.util.List;

/** English wording for a container-index query, kept pure so it can be tested without a world. */
public final class StorageText {
    private StorageText() {}

    public static String noRecord(String label) {
        return "I have no record of " + label + "; I have not looked inside a container that had it";
    }

    public static String unknown(String label) {
        return "I don't know the item " + label;
    }

    public static String found(String label, int total, List<StoredCandidate> rows) {
        StringBuilder text = new StringBuilder()
                .append(total)
                .append(' ')
                .append(label)
                .append(" recorded: ");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                text.append("; ");
            }
            text.append(row(rows.get(i)));
        }
        return text.toString();
    }

    public static String foundFreshOnly(String label, List<StoredCandidate> staleRows) {
        return "every record of " + label + " is stale";
    }

    private static String row(StoredCandidate candidate) {
        ContainerRecord record = candidate.record();
        StringBuilder text = new StringBuilder()
                .append(candidate.count())
                .append(" in a ")
                .append(record.kind())
                .append(" at ")
                .append(record.key().x())
                .append(' ')
                .append(record.key().y())
                .append(' ')
                .append(record.key().z())
                .append(" (")
                .append(record.key().dimension())
                .append(", checked ")
                .append(candidate.ageTicks())
                .append(" ticks ago");
        if (candidate.stale()) {
            text.append(", stale");
        }
        return text.append(')').toString();
    }
}
