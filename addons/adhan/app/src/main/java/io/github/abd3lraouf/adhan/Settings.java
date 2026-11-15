package io.github.abd3lraouf.adhan;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** The choices on the status screen, and what the add-on remembers between alarms. */
final class Settings {
    static final String PREFS = "adhan";
    static final int VOLUME_MIN = 10;
    static final int VOLUME_MAX = 100;
    static final int VOLUME_DEFAULT = 80;

    private Settings() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean sound(Context c) {
        return prefs(c).getBoolean("sound", true);
    }

    static void setSound(Context c, boolean on) {
        prefs(c).edit().putBoolean("sound", on).apply();
    }

    /** Adhan volume in percent of the TV's media volume. */
    static int volume(Context c) {
        int v = prefs(c).getInt("volume", VOLUME_DEFAULT);
        return Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, v));
    }

    static void setVolume(Context c, int v) {
        prefs(c).edit().putInt("volume", Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, v))).apply();
    }

    static boolean enabled(Context c, String key) {
        return !prefs(c).getBoolean("off_" + key, false);
    }

    static void setEnabled(Context c, String key, boolean on) {
        prefs(c).edit().putBoolean("off_" + key, !on).apply();
    }

    static Set<String> disabled(Context c) {
        Set<String> out = new HashSet<>();
        for (String k : Schedule.FIVE) if (!enabled(c, k)) out.add(k);
        return out;
    }

    /** The prayer's name in the TV's language. */
    static String label(Context c, String key) {
        switch (key) {
            case "Fajr": return c.getString(R.string.fajr);
            case "Sunrise": return c.getString(R.string.sunrise);
            case "Dhuhr": return c.getString(R.string.dhuhr);
            case "Asr": return c.getString(R.string.asr);
            case "Maghrib": return c.getString(R.string.maghrib);
            case "Isha": return c.getString(R.string.isha);
            default: return key;
        }
    }
}
