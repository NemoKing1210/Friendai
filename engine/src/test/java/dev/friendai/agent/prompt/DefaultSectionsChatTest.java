package dev.friendai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.route.TurnMode;
import dev.friendai.agent.turn.Persona;
import dev.friendai.agent.turn.TurnKind;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A chat turn keeps the companion's voice, its facts and its honesty rules, and loses the queue and plan guidance:
 * a greeting's tools cannot queue, plan or repair anything, so that text only competes for the model's attention.
 */
class DefaultSectionsChatTest {
    private static final TurnContext OWNER = context(AccessLevel.OWNER);
    private static final TurnContext GUEST = context(AccessLevel.GUEST);

    private static TurnContext context(AccessLevel access) {
        return new TurnContext(
                new Persona("F", "d", "auto"),
                "p",
                new Caller(UUID.randomUUID(), "S", access),
                "",
                TurnKind.PLAYER,
                "",
                List.of("- Use mine_blocks for a kind of block."),
                "");
    }

    private static String assemble(List<PromptSection> sections, TurnContext ctx) {
        return new PromptAssembler(sections, 0).assembleDetailed(ctx).text();
    }

    @Test
    void aChatTurnDropsTheQueuePlanAndHandoverRules() {
        String chat = assemble(DefaultSections.chat(), OWNER);
        for (String acting : List.of("queue_add", "queue_edit", "set_goal", "update_goal", "plan_validate")) {
            assertFalse(chat.contains(acting), acting + " is not reachable on a chat turn:\n" + chat);
        }
        assertFalse(chat.contains("Don't follow me"), "a chat turn carries no movement tools to correct");
    }

    @Test
    void aChatTurnKeepsTheRulesThatStillApply() {
        String chat = assemble(DefaultSections.chat(), OWNER);
        assertTrue(chat.contains("Keep replies short"), chat);
        assertTrue(chat.contains("Answering is never a reason to move"), chat);
        assertTrue(chat.contains("Only this turn's tools are loaded"), chat);
        assertTrue(chat.contains("Never claim you did something until the task-finished event arrives"), chat);
        assertTrue(chat.contains("Your own records"), chat);
    }

    @Test
    void aChatPromptIsSubstantiallySmallerThanAnActingOne() {
        String full = assemble(DefaultSections.all(), OWNER);
        String chat = assemble(DefaultSections.chat(), OWNER);
        assertTrue(
                chat.length() < full.length() * 0.7,
                "chat prompt is " + chat.length() + " characters against " + full.length());
    }

    @Test
    void aGuestChatTurnCarriesNoOwnerOnlyGuidance() {
        String chat = assemble(DefaultSections.chat(), GUEST);
        assertFalse(chat.contains("call player_view"), chat);
        assertFalse(chat.contains("call attack_player"), chat);
        assertTrue(chat.contains("Guests can only chat"), chat);
    }

    @Test
    void theChatAssemblerDiffersOnlyForChatTurns() {
        String chat = PromptAssembler.forMode(TurnMode.CHAT, 0).assemble(OWNER);
        String acting = PromptAssembler.forMode(TurnMode.ACTION, 0).assemble(OWNER);
        assertTrue(chat.length() < acting.length(), chat.length() + " vs " + acting.length());
        assertTrue(acting.contains("queue_add"), "an action turn keeps the queue rules");
    }
}
