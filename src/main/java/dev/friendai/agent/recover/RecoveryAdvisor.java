package dev.friendai.agent.recover;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a task's failure text into a cause and untried remedies. The rules are an ordered list; the first match
 * wins and anything unmatched is {@link FailureKind#UNKNOWN} with no advice, so the model is never misled.
 *
 * <p>A refusal that names its cause (a place, the server, the block under the feet) is not an owner decision: it
 * comes with a remedy the companion can act on. Only the generic "is protected" still asks the player.
 */
public final class RecoveryAdvisor {
    private record Rule(
            Pattern pattern,
            FailureKind kind,
            int detailGroup,
            String detailPrefix,
            List<String> remedies,
            boolean ownerOnly) {}

    private static final String TIMES = "×";

    private static final List<Rule> RULES = List.of(
            new Rule(
                    Pattern.compile("missing materials: (?:(\\d+) " + TIMES + " )?([a-z0-9_:./-]+)"),
                    FailureKind.MISSING_ITEM,
                    0,
                    "missing ",
                    List.of("obtain_item {item}"),
                    false),
            new Rule(
                    Pattern.compile("missing (hoe|axe|pickaxe|shovel|sword|shears|bucket|rod|fishing rod)"),
                    FailureKind.NO_TOOL,
                    1,
                    "no ",
                    List.of("obtain_item for a {1}", "craft_item for a {1}"),
                    false),
            new Rule(
                    Pattern.compile("is inside the [a-z]+ place \"([^\"]+)\""),
                    FailureKind.RESOURCE_NOT_FOUND,
                    1,
                    "protected by the place ",
                    List.of("mine outside {1}"),
                    false),
            new Rule(
                    Pattern.compile("is protected by the server"),
                    FailureKind.FORBIDDEN,
                    0,
                    "spawn protection or a server rule",
                    List.of("mine outside the server's protected area"),
                    false),
            new Rule(
                    Pattern.compile("is the block i am standing on"),
                    FailureKind.UNREACHABLE,
                    0,
                    "the block under the companion",
                    List.of("mine a neighbouring block instead"),
                    false),
            new Rule(
                    Pattern.compile("is on the break list"),
                    FailureKind.FORBIDDEN,
                    0,
                    "the break list",
                    List.of("mine a block that is not on the break list"),
                    false),
            new Rule(
                    Pattern.compile("(?:(?:cannot)|(?:can't)) be broken"),
                    FailureKind.FORBIDDEN,
                    0,
                    "an unbreakable block",
                    List.of("choose another block"),
                    false),
            new Rule(
                    Pattern.compile("is protected|not allowed|forbidden"),
                    FailureKind.FORBIDDEN,
                    0,
                    "protected area",
                    List.of(),
                    true),
            new Rule(
                    Pattern.compile(
                            "creeper|zombie|skeleton|spider|lava|drown|burning|too dangerous|health too low|low health"),
                    FailureKind.DANGER,
                    0,
                    "danger nearby",
                    List.of("retreat and eat or rest, then retry"),
                    false),
            new Rule(
                    Pattern.compile("can't craft ([a-z0-9_:./ -]+)"),
                    FailureKind.MISSING_ITEM,
                    1,
                    "cannot craft ",
                    List.of("get_recipe for it, then obtain_item for the missing ingredients"),
                    false),
            new Rule(
                    Pattern.compile("can't reach|couldn't reach|couldn't get back|no path|unreachable|can't scaffold"),
                    FailureKind.UNREACHABLE,
                    0,
                    "target not reachable",
                    List.of("go_to a closer open spot first", "try a different target"),
                    false),
            new Rule(
                    Pattern.compile(
                            "no (?:growing crops|furnace|beehive|cows or goats|open water|dark spots|storage container|"
                                    + "known storage container|directly visible|unvisited local frontier|observed entrance|block source)"),
                    FailureKind.RESOURCE_NOT_FOUND,
                    0,
                    "nothing suitable nearby",
                    List.of("search_resource for the material", "move to another area and look_around"),
                    false),
            new Rule(
                    Pattern.compile("can't get ([a-z0-9_:./ -]+)"),
                    FailureKind.RESOURCE_NOT_FOUND,
                    1,
                    "cannot get ",
                    List.of("search_resource for it", "plan_obtain to see what it needs"),
                    false));

    /**
     * @param tool the tool that failed (kept for future rules; no rule depends on it yet)
     * @param failureText the task's own failure summary
     * @param alreadyTried remedy strings already attempted for this goal; they are never suggested again
     */
    public Diagnosis diagnose(String tool, String failureText, Set<String> alreadyTried) {
        String text = failureText == null ? "" : failureText.toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            if (!m.find()) {
                continue;
            }
            String detail = rule.detailPrefix()
                    + (rule.detailGroup() > 0 && m.group(rule.detailGroup()) != null
                            ? m.group(rule.detailGroup()).strip()
                            : "");
            if (rule.kind() == FailureKind.MISSING_ITEM && rule.detailGroup() == 0) {
                String count = m.group(1) == null ? "1" : m.group(1);
                detail = "missing " + count + " " + m.group(2);
            }
            List<String> remedies = new ArrayList<>();
            for (String template : rule.remedies()) {
                String remedy = template;
                if (m.groupCount() >= 2 && rule.kind() == FailureKind.MISSING_ITEM && rule.detailGroup() == 0) {
                    remedy = remedy.replace("{item}", m.group(2));
                }
                for (int group = m.groupCount(); group >= 1; group--) {
                    if (m.group(group) != null) {
                        remedy =
                                remedy.replace("{" + group + "}", m.group(group).strip());
                    }
                }
                if (!alreadyTried.contains(remedy)) {
                    remedies.add(remedy);
                }
            }
            boolean exhausted = !rule.remedies().isEmpty() && remedies.isEmpty();
            boolean needsPlayer = rule.ownerOnly() || exhausted;
            return new Diagnosis(rule.kind(), detail, remedies, needsPlayer);
        }
        return new Diagnosis(FailureKind.UNKNOWN, "unclear cause", List.of(), false);
    }
}
