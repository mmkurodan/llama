package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

public final class AppLanguageManager {
    public static final String LANGUAGE_JA = "ja";
    public static final String LANGUAGE_EN = "en";
    public static final String LANGUAGE_FR = "fr";
    public static final String LANGUAGE_ES = "es";
    public static final String LANGUAGE_PT = "pt";
    public static final String LANGUAGE_DE = "de";
    public static final String LANGUAGE_IT = "it";
    public static final String LANGUAGE_ZH = "zh";
    public static final String LANGUAGE_KO = "ko";

    // Supported UI languages, in the order shown in the picker. English is the fallback.
    public static final String[] SUPPORTED_LANGUAGES = {
            LANGUAGE_JA, LANGUAGE_EN, LANGUAGE_FR, LANGUAGE_ES,
            LANGUAGE_PT, LANGUAGE_DE, LANGUAGE_IT, LANGUAGE_ZH, LANGUAGE_KO
    };

    // Endonyms for the language picker.
    public static final String[] SUPPORTED_LANGUAGE_LABELS = {
            "日本語", "English", "Français", "Español",
            "Português", "Deutsch", "Italiano", "中文", "한국어"
    };

    private static final String PREFS_NAME = "ollama_prefs";
    private static final String PREF_DISPLAY_LANGUAGE = "display_language";
    private static final String[] LEGACY_PREF_DISPLAY_LANGUAGE_KEYS = {
            "ui_language",
            "language",
            "app_language"
    };

    private AppLanguageManager() {
    }

    public static Context wrap(Context base) {
        String language = getOrInitDisplayLanguage(base);
        Locale locale = localeFor(language);
        Locale.setDefault(locale);

        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
    }

    private static Locale localeFor(String language) {
        if (language == null) {
            return Locale.ENGLISH;
        }
        switch (language) {
            case LANGUAGE_JA: return Locale.JAPANESE;
            case LANGUAGE_ZH: return Locale.SIMPLIFIED_CHINESE;
            case LANGUAGE_KO: return Locale.KOREAN;
            default:          return Locale.forLanguageTag(language);
        }
    }

    public static String getOrInitDisplayLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String current = normalizeLanguage(prefs.getString(PREF_DISPLAY_LANGUAGE, null));
        if (current != null) {
            return current;
        }

        for (String legacyKey : LEGACY_PREF_DISPLAY_LANGUAGE_KEYS) {
            String legacy = normalizeLanguage(prefs.getString(legacyKey, null));
            if (legacy != null) {
                prefs.edit().putString(PREF_DISPLAY_LANGUAGE, legacy).apply();
                return legacy;
            }
        }

        // First run: default to the system language when it is one we support, otherwise English.
        Locale locale = context.getResources().getConfiguration().getLocales().isEmpty()
                ? Locale.getDefault()
                : context.getResources().getConfiguration().getLocales().get(0);
        String detected = normalizeLanguage(locale != null ? locale.getLanguage() : null);
        if (detected == null) {
            detected = LANGUAGE_EN;
        }
        prefs.edit().putString(PREF_DISPLAY_LANGUAGE, detected).apply();
        return detected;
    }

    public static void saveDisplayLanguage(Context context, String language) {
        String normalized = normalizeLanguage(language);
        if (normalized == null) {
            normalized = LANGUAGE_EN;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_DISPLAY_LANGUAGE, normalized).commit();
    }

    public static boolean isJapanese(Context context) {
        return LANGUAGE_JA.equals(getOrInitDisplayLanguage(context));
    }

    // Index of the given language in SUPPORTED_LANGUAGES (for spinner selection); -1 if none.
    public static int indexOf(String language) {
        String n = normalizeLanguage(language);
        if (n == null) {
            return -1;
        }
        for (int i = 0; i < SUPPORTED_LANGUAGES.length; i++) {
            if (SUPPORTED_LANGUAGES[i].equals(n)) {
                return i;
            }
        }
        return -1;
    }

    static String normalizeLanguage(String language) {
        if (language == null) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        for (String supported : SUPPORTED_LANGUAGES) {
            if (normalized.startsWith(supported)) {
                return supported;
            }
        }
        return null;
    }
}
