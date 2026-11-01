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

    private static final Map<String, List<String>> OPENERS = Map.of(
            "en",
                    List.of(
                            "I keep thinking about that cave we dug out together.",
                            "The trees by the river are taller than I remembered.",
                            "What did you get up to while I was away?",
                            "The light is good today — is there anything worth building?",
                            "Do you have a plan for the night, or shall we take it easy?",
                            "Something about the quiet here makes me want to wander.",
                            "Would you like company on the next trip out?",
                            "I am curious what you keep in that chest by the door."),
            "ru",
                    List.of(
                            "Я всё вспоминаю ту пещеру, где мы вместе копали.",
                            "Деревья у реки выше, чем я думал.",
                            "Что нового у тебя было, пока меня не было?",
                            "Свет сегодня хороший — есть что построить?",
                            "У тебя есть планы на ночь, или отдохнём?",
                            "Мне нравится, как здесь тихо.",
                            "Пойдём куда-нибудь вместе, когда освободишься?",
                            "Интересно, что ты хранишь в том сундуке у двери."),
            "de",
                    List.of(
                            "Ich denke oft an die Höhle, in der wir zusammen gegraben haben.",
                            "Die Bäume am Fluss sind höher, als ich dachte.",
                            "Was hast du gemacht, während ich weg war?",
                            "Das Licht ist heute gut — gibt es etwas zu bauen?",
                            "Hast du heute Nacht einen Plan, oder lassen wir es ruhig angehen?",
                            "Diese Stille hier macht mir Lust aufs Wandern.",
                            "Was bewahrst du in der Truhe an der Tür auf?",
                            "Ich bin neugierig, woran du gerade arbeitest."),
            "fr",
                    List.of(
                            "Je repense souvent à la grotte où nous avons creusé ensemble.",
                            "Les arbres près de la rivière sont plus grands que je croyais.",
                            "Qu'as-tu fait pendant mon absence ?",
                            "La lumière est belle aujourd'hui — y a-t-il quelque chose à bâtir ?",
                            "Tu as un projet pour la nuit, ou on se repose ?",
                            "Ce calme me donne envie de marcher.",
                            "Que gardes-tu dans ce coffre près de la porte ?",
                            "Je me demande sur quoi tu travailles en ce moment."),
            "es",
                    List.of(
                            "Sigo pensando en esa cueva donde cavamos juntos.",
                            "Los árboles del río están más altos de lo que creía.",
                            "¿Qué hiciste mientras yo no estaba?",
                            "La luz está buena hoy, ¿hay algo que construir?",
                            "¿Tienes algún plan para la noche o lo tomamos con calma?",
                            "Este silencio me da ganas de caminar.",
                            "¿Qué guardas en ese cofre junto a la puerta?",
                            "Me pregunto en qué andas trabajando ahora."),
            "pt",
                    List.of(
                            "Ainda penso naquela caverna onde escavamos juntos.",
                            "As árvores perto do rio estão maiores do que eu pensava.",
                            "O que você fez enquanto eu estava fora?",
                            "A luz está boa hoje, há alguma coisa para construir?",
                            "Você tem algum plano para a noite ou vamos com calma?",
                            "Esse silêncio todo me dá vontade de caminhar.",
                            "O que você guarda naquele baú perto da porta?",
                            "Fico pensando no que você anda fazendo agora."),
            "uk",
                    List.of(
                            "Я все згадую ту печеру, де ми разом копали.",
                            "Дерева біля річки вищі, ніж я думав.",
                            "Що нового в тебе було, поки мене не було?",
                            "Світло сьогодні гарне — є що збудувати?",
                            "Маєш плани на ніч, чи відпочинемо?",
                            "Мені подобається, як тут тихо.",
                            "Ходімо кудись разом, коли звільнишся?",
                            "Цікаво, що ти тримаєш у тій скрині біля дверей."),
            "zh",
                    List.of(
                            "我总想起我们一起挖过的那个洞穴。",
                            "河边的树比我记得的还要高。",
                            "我不在的时候，你在忙什么？",
                            "今天光线不错，有什么值得建造的吗？",
                            "今晚有打算吗，还是轻松一下？",
                            "这里这么安静，让我想去走走。",
                            "下次出门，要不要我陪你一起？",
                            "我很好奇门边那个箱子里放了什么。"));

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
