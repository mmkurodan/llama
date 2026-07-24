package com.micklab.llama;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * Machine-generated UI translations for the languages beyond ja/en, keyed by the English
 * string passed to {@code localizedText(ja, en)}. Value array order: fr, es, pt, de, it, zh, ko.
 * A missing key (or language) falls back to English, so the app is always usable while the
 * table is being filled in.
 */
public final class Translations {
    private Translations() {
    }

    private static final Map<String, String[]> T = new HashMap<>();

    // en, then: fr, es, pt, de, it, zh, ko
    private static void e(String en, String fr, String es, String pt, String de, String it, String zh, String ko) {
        T.put(en, new String[]{fr, es, pt, de, it, zh, ko});
    }

    static {
        registerAll();
    }

    public static String get(Context ctx, String ja, String en) {
        String lang = AppLanguageManager.getOrInitDisplayLanguage(ctx);
        if (AppLanguageManager.LANGUAGE_JA.equals(lang)) {
            return ja;
        }
        if (AppLanguageManager.LANGUAGE_EN.equals(lang)) {
            return en;
        }
        String[] a = T.get(en);
        if (a != null) {
            int i = -1;
            switch (lang) {
                case "fr": i = 0; break;
                case "es": i = 1; break;
                case "pt": i = 2; break;
                case "de": i = 3; break;
                case "it": i = 4; break;
                case "zh": i = 5; break;
                case "ko": i = 6; break;
                default: break;
            }
            if (i >= 0 && a[i] != null) {
                return a[i];
            }
        }
        return en; // fallback to English
    }

    // Populated in Phase 2 with e("English", "Français", "Español", "Português", "Deutsch",
    // "Italiano", "中文", "한국어") entries for each localizedText English string.
    private static void registerAll() {
    }
}
