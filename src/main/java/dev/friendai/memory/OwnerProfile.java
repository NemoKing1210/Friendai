package dev.friendai.memory;

import java.util.Locale;
import java.util.IllformedLocaleException;

/** Explicit owner preferences, never included in another caller's prompt. */
public record OwnerProfile(String language, String address) {
    public static final OwnerProfile EMPTY = new OwnerProfile("auto", "");

    public OwnerProfile {
        language = language == null || language.isBlank() ? "auto" : language.strip();
        if (!language.equals("auto")) {
            if (language.length() > 35) throw new IllegalArgumentException("Invalid language tag.");
            try {
                language = new Locale.Builder().setLanguageTag(language).build().toLanguageTag();
                if (language.equals("und")) throw new IllegalArgumentException("Invalid language tag.");
            } catch (IllformedLocaleException ex) {
                throw new IllegalArgumentException("Invalid language tag.");
            }
        }
        address = CompanionDescription.plain(address, 80, false);
    }
}
