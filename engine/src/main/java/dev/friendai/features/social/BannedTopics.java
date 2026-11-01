package dev.friendai.features.social;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Subjects the companion must never open on its own, per language: grief, money trouble, health, the owner's absence
 * and anything about another player. A plain word list per language, matched on whole words — never one global regex.
 */
public final class BannedTopics {
    private static final Map<String, List<String>> TERMS = Map.of(
            "en",
            List.of(
                    "death",
                    "died",
                    "funeral",
                    "cancer",
                    "illness",
                    "divorce",
                    "debt",
                    "salary",
                    "password",
                    "address",
                    "phone",
                    "email"),
            "ru",
            List.of(
                    "смерть",
                    "умер",
                    "похороны",
                    "болезнь",
                    "рак",
                    "развод",
                    "долг",
                    "зарплата",
                    "пароль",
                    "адрес",
                    "телефон",
                    "почта"),
            "de",
            List.of("tod", "gestorben", "beerdigung", "krankheit", "krebs", "scheidung", "schulden", "passwort"),
            "fr",
            List.of("mort", "décès", "enterrement", "maladie", "cancer", "divorce", "dettes", "mot de passe"),
            "es",
            List.of("muerte", "murió", "entierro", "enfermedad", "cáncer", "divorcio", "deudas", "contraseña"),
            "pt",
            List.of("morte", "morreu", "enterro", "doença", "câncer", "divórcio", "dívidas", "senha"),
            "uk",
            List.of("смерть", "помер", "похорон", "хвороба", "рак", "розлучення", "борг", "пароль"),
            "zh",
            List.of("死亡", "去世", "葬礼", "癌症", "离婚", "债务", "密码", "地址"));

    private BannedTopics() {}

    public static boolean banned(String text, String language) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String term : TERMS.getOrDefault(code(language), TERMS.get("en"))) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /** A language code or the learned name, reduced to the two-letter code the list is keyed by. */
    public static String code(String language) {
        if (language == null || language.isBlank() || "auto".equals(language)) {
            return "en";
        }
        String lower = language.strip().toLowerCase(Locale.ROOT);
        if (TERMS.containsKey(lower)) {
            return lower;
        }
        return switch (lower) {
            case "russian", "русский" -> "ru";
            case "german", "deutsch" -> "de";
            case "french", "français" -> "fr";
            case "spanish", "español" -> "es";
            case "portuguese", "português" -> "pt";
            case "ukrainian", "українська" -> "uk";
            case "chinese", "中文" -> "zh";
            default -> "en";
        };
    }
}
