package dev.friendai.features.instructions;

import java.util.*;

/** Bounded model-readable catalogue: ids and metadata, never bodies. */
public final class SkillCatalogue {
    public static final int PAGE_SIZE = 12;
    static final int NAME_VIEW = 60, DESCRIPTION_VIEW = 90;
    /** The worst legal page stays under this: 12 rows of bounded fields plus the header and pager line. */
    public static final int MAX_CHARS = 3600;

    private SkillCatalogue() {}

    public static String render(List<InstructionSkill> all, Set<String> disabled, int page) {
        if (all.isEmpty())
            return "No instruction skills are recorded yet. Use manage_skill after a finished goal or a failed"
                    + " shift, and remember for facts.";
        List<InstructionSkill> rows =
                all.stream().sorted(Comparator.comparing(InstructionSkill::id)).toList();
        int pages = (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int index = Math.clamp(page, 0, pages - 1);
        StringBuilder out = new StringBuilder("Instruction skills: ")
                .append(rows.size())
                .append(" total; page ")
                .append(index + 1)
                .append(" of ")
                .append(pages)
                .append(" (")
                .append(PAGE_SIZE)
                .append(" per page). Ids are exact; read a body with use_skill.\n");
        int from = index * PAGE_SIZE, to = Math.min(rows.size(), from + PAGE_SIZE);
        for (var s : rows.subList(from, to)) {
            out.append("- ")
                    .append(s.id())
                    .append(" | ")
                    .append(oneLine(s.name(), NAME_VIEW))
                    .append(" | ")
                    .append(s.source().name().toLowerCase(Locale.ROOT))
                    .append(" | ")
                    .append(s.activation().name().toLowerCase(Locale.ROOT))
                    .append(" | v")
                    .append(s.version())
                    .append(" | ")
                    .append(s.enabled() && !disabled.contains(s.id()) ? "on" : "off")
                    .append(" | ")
                    .append(oneLine(s.description(), DESCRIPTION_VIEW))
                    .append('\n');
        }
        if (to < rows.size())
            out.append("More: call list_skills with page ").append(index + 1).append(".\n");
        else if (index > 0) out.append("This is the last page.\n");
        return out.toString();
    }

    static String oneLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }
}
