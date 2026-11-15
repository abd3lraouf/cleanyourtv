package com.gathin.tvhome;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * wallpaper_moods: which group of a rotating wallpaper collection fits the time of day. Morning from sunrise to noon,
 * evening until half an hour after sunset, night the rest. The sun is the one at config.xml's latitude and longitude,
 * worked out offline by {@link Sun} as the sky does, or 07:00 and 18:30 without a place. A group that is missing, or
 * has none of the collection's pictures, falls back to the whole collection.
 */
final class WallpaperMood {
    static final String MORNING = "morning", EVENING = "evening", NIGHT = "night";
    static final String[] ALL = {MORNING, EVENING, NIGHT};
    /** Groups named after the day's prayers, used instead of the three above when the theme has a "fajr" group. */
    static final String[] PRAYER_GROUPS = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};
    /** Today's six prayer times in minutes of the day, from the prayer panel; null without them. */
    static volatile int[] prayerMinutes;
    /** Sunrise and sunset in minutes of the day, without a place (or where the sun does not rise or set that day). */
    static final int DEFAULT_SUNRISE = 7 * 60, DEFAULT_SUNSET = 18 * 60 + 30;
    private static final int NOON = 12 * 60;
    /** Evening lasts this long after sunset, while the sky still glows. */
    private static final int DUSK_MINUTES = 30;

    private WallpaperMood() {
    }

    /** The mood at this minute of the day, for a sunrise and sunset in minutes of the day. */
    static String timeOfDay(int minute, int sunrise, int sunset) {
        if (minute >= sunrise && minute < NOON) return MORNING;
        if (minute >= NOON && minute < sunset + DUSK_MINUTES) return EVENING;
        return NIGHT;
    }

    /**
     * The group for this minute: the prayer whose time last passed (Isha before Fajr) when the groups have "fajr"
     * and today's prayer times are known, otherwise {@link #timeOfDay}.
     */
    static String pick(int minute, int sunrise, int sunset, Map<String, Set<String>> groups) {
        int[] p = prayerMinutes;
        if (p != null && p.length == 6 && groups.containsKey("fajr")) {
            if (minute < p[0]) return "isha";
            for (int i = 5; i >= 0; i--) if (minute >= p[i]) return PRAYER_GROUPS[i];
        }
        return timeOfDay(minute, sunrise, sunset);
    }

    /** That day's {sunrise, sunset} at config.xml's latitude and longitude; the defaults when either is unusable. */
    static int[] sun(LocalDate day, String latitude, String longitude, ZoneId zone) {
        int[] times = null;
        try {
            if (!latitude.trim().isEmpty() && !longitude.trim().isEmpty()) {
                times = Sun.riseAndSet(day, Double.parseDouble(latitude.trim()), Double.parseDouble(longitude.trim()), zone);
            }
        } catch (NumberFormatException ignored) {
            // not a number: the defaults
        }
        return times != null ? times : new int[]{DEFAULT_SUNRISE, DEFAULT_SUNSET};
    }

    /** The pictures in the mood's group, in the collection's order; all of them when the group has none. */
    static List<File> select(List<File> all, Map<String, Set<String>> groups, String mood) {
        Set<String> names = groups.get(mood);
        if (names == null) return all;
        List<File> group = new ArrayList<>();
        for (File file : all) if (names.contains(file.getName())) group.add(file);
        return group.isEmpty() ? all : group;
    }
}
