package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

public final class AppLanguageManager {
    public static final String LANGUAGE_JA = "ja";
    public static final String LANGUAGE_EN = "en";

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
        Locale locale = LANGUAGE_JA.equals(language) ? Locale.JAPANESE : Locale.ENGLISH;
        Locale.setDefault(locale);

        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);
        return base.createConfigurationContext(configuration);
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

        Locale locale = context.getResources().getConfiguration().getLocales().isEmpty()
                ? Locale.getDefault()
                : context.getResources().getConfiguration().getLocales().get(0);
        String detected = (locale != null && locale.getLanguage().toLowerCase(Locale.ROOT).startsWith("ja"))
                ? LANGUAGE_JA
                : LANGUAGE_EN;
        prefs.edit().putString(PREF_DISPLAY_LANGUAGE, detected).apply();
        return detected;
    }

    public static void saveDisplayLanguage(Context context, String language) {
        String normalized = normalizeLanguage(language);
        if (normalized == null) {
            normalized = LANGUAGE_EN;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_DISPLAY_LANGUAGE, normalized).apply();
    }

    public static boolean isJapanese(Context context) {
        return LANGUAGE_JA.equals(getOrInitDisplayLanguage(context));
    }

    private static String normalizeLanguage(String language) {
        if (language == null) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("ja")) {
            return LANGUAGE_JA;
        }
        if (normalized.startsWith("en")) {
            return LANGUAGE_EN;
        }
        return null;
    }
}
