package dev.friendai.agent.prompt;

import dev.friendai.agent.llm.model.SystemPrompt;
import dev.friendai.agent.route.TurnMode;
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

    /**
     * The prompt for one routed mode: a conversational turn carries the lighter section set, because the queue and
     * plan rules describe work its tools cannot do. Every other mode keeps the full prompt.
     */
    public static PromptAssembler forMode(TurnMode mode, int maxChars) {
        return new PromptAssembler(mode == TurnMode.CHAT ? DefaultSections.chat() : DefaultSections.all(), maxChars);
    }

    /**
     * @param system the assembled prompt, with the boundary between its stable prefix and its per-turn remainder
     * @param keptSectionIds the sections that survived the budget, in the order they were rendered
     * @param mandatoryOverrun whether uncuttable sections alone exceed the budget
     */
    public record Assembly(SystemPrompt system, List<String> keptSectionIds, boolean mandatoryOverrun) {
        public String text() {
            return system.text();
        }
    }

    public String assemble(TurnContext ctx) {
        return assembleDetailed(ctx).text();
    }

    public Assembly assembleDetailed(TurnContext ctx) {
        List<PromptSection> kept = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<PromptSection> all = new ArrayList<>(sections);
        all.addAll(ctx.instructionSections());
        for (PromptSection section : all) {
            Optional<String> render = section.render(ctx);
            if (render.isEmpty()) {
                continue;
            }
            // A section must not end in a newline: the join below adds exactly one between sections, and a second
            // would open a blank line in the prompt.
            String text = render.get();
            while (text.endsWith("\n")) {
                text = text.substring(0, text.length() - 1);
            }
            if (!text.isEmpty()) {
                kept.add(section);
                texts.add(text);
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
        // The text a turn repeats comes first and the text it changes follows: a provider caches a prefix, so a
        // volatile section ahead of a stable one would cost the cache on every turn.
        List<String> stable = new ArrayList<>();
        List<String> volatileTexts = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            (kept.get(i).stable() ? stable : volatileTexts).add(texts.get(i));
        }
        SystemPrompt system = new SystemPrompt(String.join("\n", stable), String.join("\n", volatileTexts));
        return new Assembly(
                system,
                kept.stream().map(PromptSection::id).toList(),
                maxChars > 0 && system.text().length() > maxChars);
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
