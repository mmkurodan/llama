package com.micklab.llama;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

public final class AppThemeManager {
    private static final String PREFS_NAME = "ollama_prefs";
    static final String PREF_DARK_MODE = "dark_mode";

    private AppThemeManager() {}

    public static boolean isDarkMode(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_DARK_MODE, false);
    }

    public static void saveDarkMode(Context context, boolean dark) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_DARK_MODE, dark).commit();
    }

    /** Wraps the given context so resources resolve using the saved dark/light mode. */
    public static Context wrap(Context base) {
        boolean dark = isDarkMode(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        int nightBit = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightBit;
        return base.createConfigurationContext(config);
    }
}
