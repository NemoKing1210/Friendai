package dev.friendai.features.social;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small, static openers the companion may raise when it has nothing to report, one bank per language.
 *
 * <p>Written in the language the owner actually writes in, because an English opener spoken to a Russian owner is
 * worse than silence. Each opener is at most {@link #MAX_LENGTH} characters and asks at most one question, so the
 * turn can be told not to ask when the cadence is still waiting for an earlier answer. A code the bank does not
 * know, and the blank case where nothing was learned yet, fall back to English.
 *
 * <p>Pure: no model call, no I/O, no per-call allocation — the lists are built once and returned as they are.
 */
public final class TopicBank {
    /** Openers per language. Small on purpose: a topic repeated in a session is worse than a quiet hour. */
    public static final int MAX_OPENERS = 8;
    /** Longest opener, characters. A line built on it still fits a chat message. */
    public static final int MAX_LENGTH = 120;
    /** The language an unknown code and the not-learned-yet case fall back to. */
    public static final String DEFAULT_CODE = "en";

    /** Latin and full-width question marks; Spanish also opens a question with an inverted one. */
    private static final char[] QUESTION_MARKS = {'?', '？'};

    /** The two-letter code for the names the detector can produce, so a name and a code both work. */
    private static final Map<String, String> BY_NAME = Map.of(
            "english", "en",
            "russian", "ru",
            "german", "de",
            "french", "fr",
            "spanish", "es",
            "portuguese", "pt",
            "ukrainian", "uk",
            "chinese", "zh");

    /**
     * Every opener is a neutral question about plans, preferences or work. None states a past event, a
     * definite place or object, or anything the companion could not have observed, so a line built on one
     * cannot invent a shared memory.
     */
    private static final Map<String, List<String>> OPENERS = Map.of(
            "en",
                    List.of(
                            "What would you like to work on next?",
                            "Is there anything you would like help with?",
                            "What should we do next, if anything?",
                            "Would you like to head out somewhere together?",
                            "What kind of work do you enjoy most?",
                            "Is there a job you have been putting off?",
                            "Would you like me to gather anything while I am out?",
                            "What would make today go better for you?"),
            "ru",
                    List.of(
                            "Над чем хочешь поработать дальше?",
                            "Нужна ли тебе в чём-нибудь помощь?",
                            "Чем займёмся дальше, если хочешь?",
                            "Хочешь куда-нибудь сходить вместе?",
                            "Какая работа тебе больше всего нравится?",
                            "Есть дело, которое ты откладываешь?",
                            "Собрать что-нибудь, пока я буду в отлучке?",
                            "Что сделало бы сегодняшний день лучше?"),
            "de",
                    List.of(
                            "Woran möchtest du als Nächstes arbeiten?",
                            "Kann ich dir bei etwas helfen?",
                            "Was machen wir als Nächstes, wenn du magst?",
                            "Möchtest du zusammen irgendwohin gehen?",
                            "Welche Arbeit magst du am liebsten?",
                            "Gibt es eine Aufgabe, die du aufschiebst?",
                            "Soll ich unterwegs etwas besorgen?",
                            "Was würde deinen Tag besser machen?"),
            "fr",
                    List.of(
                            "Sur quoi veux-tu travailler ensuite ?",
                            "Puis-je t'aider pour quelque chose ?",
                            "Que faisons-nous ensuite, si tu veux ?",
                            "Tu veux sortir quelque part ensemble ?",
                            "Quel genre de travail préfères-tu ?",
                            "Y a-t-il une tâche que tu repousses ?",
                            "Je te rapporte quelque chose en chemin ?",
                            "Qu'est-ce qui rendrait ta journée meilleure ?"),
            "es",
                    List.of(
                            "¿En qué quieres trabajar ahora?",
                            "¿Puedo ayudarte con algo?",
                            "¿Qué hacemos después, si quieres?",
                            "¿Quieres salir a algún sitio juntos?",
                            "¿Qué tipo de trabajo te gusta más?",
                            "¿Hay alguna tarea que estés posponiendo?",
                            "¿Te traigo algo mientras estoy fuera?",
                            "¿Qué haría mejor tu día?"),
            "pt",
                    List.of(
                            "No que você quer trabalhar agora?",
                            "Posso ajudar em alguma coisa?",
                            "O que fazemos depois, se você quiser?",
                            "Quer sair para algum lugar juntos?",
                            "De que tipo de trabalho você mais gosta?",
                            "Há alguma tarefa que você está adiando?",
                            "Devo trazer algo enquanto estou fora?",
                            "O que deixaria o seu dia melhor?"),
            "uk",
                    List.of(
                            "Над чим хочеш попрацювати далі?",
                            "Чи потрібна тобі в чомусь допомога?",
                            "Чим займемося далі, якщо хочеш?",
                            "Хочеш кудись сходити разом?",
                            "Яка робота тобі найбільше подобається?",
                            "Є справа, яку ти відкладаєш?",
                            "Зібрати щось, поки мене не буде?",
                            "Що зробило б сьогоднішній день кращим?"),
            "zh",
                    List.of(
                            "接下来你想做什么？",
                            "有什么需要我帮忙的吗？",
                            "如果愿意，我们接下来做什么？",
                            "想一起去什么地方吗？",
                            "你最喜欢做什么样的工作？",
                            "有一直拖着没做的事吗？",
                            "我出去的时候要带点什么回来吗？",
                            "什么能让今天过得更顺心？"));

    private TopicBank() {}

    /**
     * The openers for a language code ({@code en}, {@code ru}, …) or for the name the detector learned
     * ({@code Russian}); an unknown or blank value falls back to {@link #DEFAULT_CODE}.
     */
    public static List<String> forLanguage(String languageOrCode) {
        List<String> openers = OPENERS.get(codeOf(languageOrCode));
        return openers == null ? OPENERS.get(DEFAULT_CODE) : openers;
    }

    /** The two-letter code for a code or a learned language name; blank and unknown values answer the default. */
    public static String codeOf(String languageOrCode) {
        if (languageOrCode == null) {
            return DEFAULT_CODE;
        }
        String token = languageOrCode.strip().toLowerCase(Locale.ROOT);
        if (token.isEmpty()) {
            return DEFAULT_CODE;
        }
        return BY_NAME.getOrDefault(token, token);
    }

    /** Whether the opener expects an answer: it carries a question mark, Latin or full-width. */
    public static boolean asks(String opener) {
        if (opener == null) {
            return false;
        }
        for (char mark : QUESTION_MARKS) {
            if (opener.indexOf(mark) >= 0) {
                return true;
            }
        }
        return false;
    }
}
