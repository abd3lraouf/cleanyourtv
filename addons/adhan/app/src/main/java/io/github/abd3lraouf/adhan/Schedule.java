package io.github.abd3lraouf.adhan;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * The time logic, kept free of Android so it is unit tested: which prayer rings next, when, whether an alarm that
 * arrived is too late to ring, and when to look again for new times. The Prayer Times add-on gives today's times as
 * minutes after local midnight plus the day they belong to ({@code yyyy-MM-dd}); this turns them into instants.
 */
final class Schedule {
    /** The five prayers with an adhan, in the day's order. Sunrise has none. */
    static final String[] FIVE = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    /** An alarm that arrives later than this (TV asleep, clock changed) is not rung: the moment has passed. */
    static final long LATE_MS = 10 * 60_000L;
    /** While Prayer Times has no times for today, look again this often. */
    static final long RETRY_MS = 20 * 60_000L;
    /** After midnight, wait this long before asking for the new day, so Prayer Times has fetched it. */
    static final long AFTER_MIDNIGHT_MS = 3 * 60_000L;
    /** Times kept from an earlier day are used for this many days while no fresh ones arrive. */
    static final int CARRY_DAYS = 3;

    private Schedule() {}

    /** One row of the provider: a prayer key, its minute of the day and the day. */
    static final class Row {
        final String key;
        final int minutes;
        final String day;

        Row(String key, int minutes, String day) {
            this.key = key;
            this.minutes = minutes;
            this.day = day;
        }
    }

    /** The prayer that rings next and the instant it rings. */
    static final class Slot {
        final String key;
        final int minutes;
        final long at;

        Slot(String key, int minutes, long at) {
            this.key = key;
            this.minutes = minutes;
            this.at = at;
        }
    }

    static boolean isFive(String key) {
        for (String k : FIVE) if (k.equals(key)) return true;
        return false;
    }

    /** The instant of {@code minutes} after midnight on {@code day} in {@code tz}, or -1 when either is malformed. */
    static long at(String day, int minutes, TimeZone tz) {
        int[] ymd = parseDay(day);
        if (ymd == null || minutes < 0 || minutes >= 24 * 60) return -1;
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(ymd[0], ymd[1] - 1, ymd[2], minutes / 60, minutes % 60, 0);
        return c.getTimeInMillis();
    }

    /** {@code yyyy-MM-dd} for {@code now} in {@code tz}. */
    static String day(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Whole days from {@code from} to {@code to} (both {@code yyyy-MM-dd}), or Integer.MAX_VALUE when malformed. */
    static int daysBetween(String from, String to) {
        long a = at(from, 12 * 60, TimeZone.getTimeZone("UTC"));
        long b = at(to, 12 * 60, TimeZone.getTimeZone("UTC"));
        if (a < 0 || b < 0) return Integer.MAX_VALUE;
        return (int) Math.round((b - a) / 86_400_000.0);
    }

    /**
     * The next of the five prayers strictly after {@code now} whose key is not in {@code disabled}, or null when
     * none is left in the rows given (after Isha, or no rows).
     */
    static Slot next(List<Row> rows, long now, Set<String> disabled, TimeZone tz) {
        Slot best = null;
        for (Row r : rows) {
            if (!isFive(r.key) || disabled.contains(r.key)) continue;
            long t = at(r.day, r.minutes, tz);
            if (t <= now) continue;
            if (best == null || t < best.at) best = new Slot(r.key, r.minutes, t);
        }
        return best;
    }

    /** When to ask Prayer Times for the next day: a few minutes after the coming local midnight. */
    static long refreshAt(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() + AFTER_MIDNIGHT_MS;
    }

    /** True when an alarm meant for {@code at} that arrives at {@code now} should no longer ring. */
    static boolean late(long at, long now) {
        return now - at > LATE_MS;
    }

    /**
     * Times kept from {@code cachedDay}, moved to {@code today}: prayer times drift by a minute or two a day, so they
     * stand in while Prayer Times is offline. Empty when they are older than {@link #CARRY_DAYS} or from the future.
     */
    static List<Row> carryOver(List<Row> cached, String cachedDay, String today) {
        int age = daysBetween(cachedDay, today);
        if (age < 0 || age > CARRY_DAYS) return Collections.emptyList();
        List<Row> out = new ArrayList<>();
        for (Row r : cached) out.add(new Row(r.key, r.minutes, today));
        return out;
    }

    /** Rows as {@code Fajr=290,Dhuhr=698}, for keeping in preferences. */
    static String encode(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) {
            if (sb.length() > 0) sb.append(',');
            sb.append(r.key).append('=').append(r.minutes);
        }
        return sb.toString();
    }

    /** The reverse of {@link #encode}, dated {@code day}; malformed parts are skipped. */
    static List<Row> decode(String s, String day) {
        List<Row> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                int m = Integer.parseInt(part.substring(eq + 1).trim());
                if (m >= 0 && m < 24 * 60) out.add(new Row(part.substring(0, eq).trim(), m, day));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return out;
    }

    private static int[] parseDay(String day) {
        if (day == null || day.length() != 10 || day.charAt(4) != '-' || day.charAt(7) != '-') return null;
        try {
            int y = Integer.parseInt(day.substring(0, 4));
            int m = Integer.parseInt(day.substring(5, 7));
            int d = Integer.parseInt(day.substring(8, 10));
            if (m < 1 || m > 12 || d < 1 || d > 31) return null;
            return new int[]{y, m, d};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
