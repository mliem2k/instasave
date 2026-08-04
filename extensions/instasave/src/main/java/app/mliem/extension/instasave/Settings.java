package app.mliem.extension.instasave;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The user-facing InstaSave preferences, and the one place they are read and written.
 *
 * <p>Backed by a single {@link SharedPreferences} file so the settings screen and the features
 * that honour the settings never disagree on a key or a default. Every read tolerates a missing
 * Context (before the app context is captured) by returning the default, so a feature that
 * consults a preference early never crashes.
 */
public final class Settings {

    static final String PREFS = "instasave_settings";

    static final String KEY_AUTO_UPDATE = "auto_update";
    static final String KEY_HIGHEST_QUALITY = "highest_quality";

    private static final boolean DEFAULT_AUTO_UPDATE = true;
    private static final boolean DEFAULT_HIGHEST_QUALITY = true;

    private Settings() {
    }

    private static SharedPreferences prefs(Context preferred) {
        Context context = InstaSave.context(preferred);
        return context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the updater checks GitHub on its own. The "check now" button ignores this. */
    public static boolean autoUpdate() {
        SharedPreferences p = prefs(null);
        return p == null ? DEFAULT_AUTO_UPDATE : p.getBoolean(KEY_AUTO_UPDATE, DEFAULT_AUTO_UPDATE);
    }

    /** On saves the largest video variant; off saves the smallest (a data saver choice). */
    public static boolean highestQuality() {
        SharedPreferences p = prefs(null);
        return p == null ? DEFAULT_HIGHEST_QUALITY : p.getBoolean(KEY_HIGHEST_QUALITY, DEFAULT_HIGHEST_QUALITY);
    }

    static void putBoolean(Context context, String key, boolean value) {
        SharedPreferences p = prefs(context);
        if (p != null) {
            p.edit().putBoolean(key, value).apply();
        }
    }

    static boolean getBoolean(Context context, String key, boolean fallback) {
        SharedPreferences p = prefs(context);
        return p == null ? fallback : p.getBoolean(key, fallback);
    }
}
