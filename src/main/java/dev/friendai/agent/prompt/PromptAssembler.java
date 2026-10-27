package dev.friendai.agent.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Joins prompt sections in order and, over a character budget, cuts the lowest-priority sections first. */
public final class PromptAssembler {
    private final List<PromptSection> sections;
    private final int maxChars;

    /** @param maxChars the budget in characters; zero or less means unlimited */
    public PromptAssembler(List<PromptSection> sections, int maxChars) {
        this.sections = List.copyOf(sections);
        this.maxChars = maxChars;
    }

    /** The standard companion prompt under the given character budget (zero or less: unlimited). */
    public static PromptAssembler defaults(int maxChars) {
        return new PromptAssembler(DefaultSections.all(), maxChars);
    }

    public record Assembly(String text, List<String> keptSectionIds, boolean mandatoryOverrun) {}
    public String assemble(TurnContext ctx) { return assembleDetailed(ctx).text(); }
    public Assembly assembleDetailed(TurnContext ctx) {
        List<PromptSection> kept = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<PromptSection> all = new ArrayList<>(sections);
        all.addAll(ctx.instructionSections());
        for (PromptSection section : all) {
            Optional<String> text = section.render(ctx);
            if (text.isPresent() && !text.get().isEmpty()) {
                kept.add(section);
                texts.add(text.get());
            }
        }
        if (maxChars > 0) {
            while (length(texts) > maxChars) {
                int cut = lowestCuttable(kept);
                if (cut < 0) {
                    break;
                }
                kept.remove(cut);
                texts.remove(cut);
            }
        }
        String text = String.join("\n", texts);
        return new Assembly(text, kept.stream().map(PromptSection::id).toList(), maxChars > 0 && text.length() > maxChars);
    }

    private static int length(List<String> texts) {
        int total = Math.max(0, texts.size() - 1);
        for (String text : texts) {
            total += text.length();
        }
        return total;
    }

    /** Index of the lowest-priority cuttable section; among equals, the last listed. */
    private static int lowestCuttable(List<PromptSection> kept) {
        int index = -1;
        int lowest = PromptSection.NEVER_CUT;
        for (int i = 0; i < kept.size(); i++) {
            int priority = kept.get(i).priority();
            if (priority != PromptSection.NEVER_CUT && priority <= lowest) {
                lowest = priority;
                index = i;
            }
        }
        return index;
    }
}
