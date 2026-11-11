package com.traverse.util;

import android.content.Context;
import android.content.SharedPreferences;

/** Learner preferences. Language only — the app is offline and has no account. */
public class Prefs {
    private static final String FILE = "traverse";

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** "java" or "python" — which language code samples appear in. */
    public static String lang(Context c) { return p(c).getString("lang", "java"); }

    public static void setLang(Context c, String v) {
        if (v == null) return;
        String lang = v.trim().toLowerCase();
        if (!"python".equals(lang)) lang = "java";
        p(c).edit().putString("lang", lang).apply();
    }
}
