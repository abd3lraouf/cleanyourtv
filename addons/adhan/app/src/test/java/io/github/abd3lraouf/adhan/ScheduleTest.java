package io.github.abd3lraouf.adhan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

public class ScheduleTest {
    private static final TimeZone CAIRO = TimeZone.getTimeZone("Africa/Cairo");
    private static final String DAY = "2026-11-15";
    private static final Set<String> NONE = Collections.emptySet();

    private static List<Schedule.Row> today() {
        return Arrays.asList(
                new Schedule.Row("Fajr", 290, DAY),
                new Schedule.Row("Sunrise", 381, DAY),
                new Schedule.Row("Dhuhr", 698, DAY),
                new Schedule.Row("Asr", 875, DAY),
                new Schedule.Row("Maghrib", 1015, DAY),
                new Schedule.Row("Isha", 1096, DAY));
    }

    private static long t(int minutes) {
        return Schedule.at(DAY, minutes, CAIRO);
    }

    @Test public void atIsLocalWallClock() {
        // 2026-11-15 04:50 in Cairo (UTC+2 in November) is 02:50 UTC.
        assertEquals(1_794_711_000_000L, Schedule.at(DAY, 290, CAIRO));
        assertEquals("02:50", hhmm(Schedule.at(DAY, 290, CAIRO), TimeZone.getTimeZone("UTC")));
        assertEquals(DAY, Schedule.day(Schedule.at(DAY, 290, CAIRO), CAIRO));
    }

    @Test public void atRejectsMalformed() {
        assertEquals(-1, Schedule.at("2026/11/15", 290, CAIRO));
        assertEquals(-1, Schedule.at(null, 290, CAIRO));
        assertEquals(-1, Schedule.at("2026-13-01", 290, CAIRO));
        assertEquals(-1, Schedule.at(DAY, -1, CAIRO));
        assertEquals(-1, Schedule.at(DAY, 1440, CAIRO));
    }

    @Test public void nextSkipsSunrise() {
        Schedule.Slot s = Schedule.next(today(), t(300), NONE, CAIRO);
        assertEquals("Dhuhr", s.key);
        assertEquals(t(698), s.at);
    }

    @Test public void nextBeforeFajrIsFajr() {
        assertEquals("Fajr", Schedule.next(today(), t(10), NONE, CAIRO).key);
    }

    @Test public void nextIsStrictlyAfterNow() {
        assertEquals("Asr", Schedule.next(today(), t(698), NONE, CAIRO).key);
        assertEquals("Dhuhr", Schedule.next(today(), t(698) - 1, NONE, CAIRO).key);
    }

    @Test public void nextSkipsDisabled() {
        Set<String> off = new HashSet<>(Arrays.asList("Dhuhr", "Asr"));
        assertEquals("Maghrib", Schedule.next(today(), t(400), off, CAIRO).key);
    }

    @Test public void nothingAfterIsha() {
        assertNull(Schedule.next(today(), t(1100), NONE, CAIRO));
        assertNull(Schedule.next(Collections.emptyList(), t(100), NONE, CAIRO));
    }

    @Test public void carryOverMovesToToday() {
        List<Schedule.Row> moved = Schedule.carryOver(today(), DAY, "2026-11-16");
        assertEquals(6, moved.size());
        assertEquals("2026-11-16", moved.get(0).day);
        assertEquals(290, moved.get(0).minutes);
        // After Isha today, tomorrow's Fajr comes from today's times.
        Schedule.Slot s = Schedule.next(moved, t(1100), NONE, CAIRO);
        assertEquals("Fajr", s.key);
        assertEquals(Schedule.at("2026-11-16", 290, CAIRO), s.at);
    }

    @Test public void carryOverHasALimit() {
        assertEquals(6, Schedule.carryOver(today(), DAY, "2026-11-18").size());
        assertTrue(Schedule.carryOver(today(), DAY, "2026-11-19").isEmpty());
        assertTrue(Schedule.carryOver(today(), DAY, "2026-11-14").isEmpty());
        assertTrue(Schedule.carryOver(today(), "", DAY).isEmpty());
    }

    @Test public void daysBetweenAcrossMonthAndYear() {
        assertEquals(1, Schedule.daysBetween("2026-11-30", "2026-12-01"));
        assertEquals(1, Schedule.daysBetween("2026-12-31", "2027-01-01"));
        assertEquals(0, Schedule.daysBetween(DAY, DAY));
        assertEquals(Integer.MAX_VALUE, Schedule.daysBetween("x", DAY));
    }

    @Test public void refreshIsJustAfterNextMidnight() {
        long r = Schedule.refreshAt(t(1100), CAIRO);
        assertEquals(Schedule.at("2026-11-16", 3, CAIRO), r);
        assertEquals(Schedule.at("2026-11-16", 3, CAIRO), Schedule.refreshAt(t(0), CAIRO));
    }

    @Test public void refreshAcrossSummerTimeChange() {
        // Egypt leaves summer time on the last Thursday of October: 2026-10-30 00:00 local.
        String before = "2026-10-29";
        long r = Schedule.refreshAt(Schedule.at(before, 1200, CAIRO), CAIRO);
        assertEquals("2026-10-30", Schedule.day(r, CAIRO));
        assertEquals("00:03", hhmm(r, CAIRO));
    }

    @Test public void lateAfterTenMinutes() {
        assertFalse(Schedule.late(t(698), t(698)));
        assertFalse(Schedule.late(t(698), t(698) + Schedule.LATE_MS));
        assertTrue(Schedule.late(t(698), t(698) + Schedule.LATE_MS + 1));
        assertFalse(Schedule.late(t(698), t(697)));
    }

    @Test public void encodeDecodeRoundTrip() {
        String s = Schedule.encode(today());
        assertEquals("Fajr=290,Sunrise=381,Dhuhr=698,Asr=875,Maghrib=1015,Isha=1096", s);
        List<Schedule.Row> back = Schedule.decode(s, DAY);
        assertEquals(6, back.size());
        assertEquals("Isha", back.get(5).key);
        assertEquals(1096, back.get(5).minutes);
        assertEquals(DAY, back.get(5).day);
    }

    @Test public void decodeSkipsJunk() {
        List<Schedule.Row> rows = Schedule.decode("Fajr=290,bad,=5,Asr=x,Isha=9999,Dhuhr=698", DAY);
        assertEquals(2, rows.size());
        assertEquals("Dhuhr", rows.get(1).key);
        assertTrue(Schedule.decode(null, DAY).isEmpty());
        assertTrue(Schedule.decode("", DAY).isEmpty());
    }

    private static String hhmm(long at, TimeZone tz) {
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(at);
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }
}
