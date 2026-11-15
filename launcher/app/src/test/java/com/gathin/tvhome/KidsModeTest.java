package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.Test;

public class KidsModeTest {
    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");
    private static final long MIN = 60_000L;

    private static long at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).atZone(CAIRO).toInstant().toEpochMilli();
    }

    @Test
    public void pinIsFourToEightDigits() {
        assertTrue(KidsMode.validPin("1234"));
        assertTrue(KidsMode.validPin("12345678"));
        assertFalse(KidsMode.validPin("123"));
        assertFalse(KidsMode.validPin("123456789"));
        assertFalse(KidsMode.validPin("12a4"));
        assertFalse(KidsMode.validPin(""));
        assertFalse(KidsMode.validPin(null));
    }

    @Test
    public void storedPinIsSaltedHashThatMatchesOnlyTheSamePin() {
        String salt = KidsMode.newSalt(new SecureRandom());
        String stored = KidsMode.hashPin("2468", salt);
        assertFalse(stored.contains("2468"));
        assertTrue(KidsMode.pinMatches(stored, "2468"));
        assertFalse(KidsMode.pinMatches(stored, "2469"));
        assertFalse(KidsMode.pinMatches(stored, ""));
        assertFalse(KidsMode.pinMatches(null, "2468"));
        assertFalse(KidsMode.pinMatches("garbage", "2468"));
        assertNotEquals(stored, KidsMode.hashPin("2468", salt + "0"));
    }

    @Test
    public void segmentAddsToTodaysTotal() {
        long day = KidsMode.epochDay(at(15, 10, 0), CAIRO);
        long[] r = KidsMode.fold(day, 10 * MIN, at(15, 10, 0), at(15, 10, 25), CAIRO);
        assertEquals(day, r[0]);
        assertEquals(35 * MIN, r[1]);
    }

    @Test
    public void noOpenSegmentAddsNothing() {
        long day = KidsMode.epochDay(at(15, 10, 0), CAIRO);
        assertEquals(10 * MIN, KidsMode.fold(day, 10 * MIN, 0, at(15, 11, 0), CAIRO)[1]);
    }

    @Test
    public void newDayStartsFromZeroAndCountsOnlyAfterMidnight() {
        long yesterday = KidsMode.epochDay(at(14, 23, 0), CAIRO);
        long[] r = KidsMode.fold(yesterday, 80 * MIN, at(14, 23, 50), at(15, 0, 20), CAIRO);
        assertEquals(yesterday + 1, r[0]);
        assertEquals(20 * MIN, r[1]);
        // A day without a segment just resets.
        assertEquals(0, KidsMode.fold(yesterday, 80 * MIN, 0, at(15, 9, 0), CAIRO)[1]);
    }

    @Test
    public void aSegmentNeverCountsMoreThanSixHours() {
        long day = KidsMode.epochDay(at(15, 1, 0), CAIRO);
        assertEquals(KidsMode.MAX_SEGMENT_MS, KidsMode.fold(day, 0, at(15, 1, 0), at(15, 23, 0), CAIRO)[1]);
    }

    @Test
    public void clockGoingBackwardsAddsNothing() {
        long day = KidsMode.epochDay(at(15, 10, 0), CAIRO);
        assertEquals(5 * MIN, KidsMode.fold(day, 5 * MIN, at(15, 10, 0), at(15, 9, 0), CAIRO)[1]);
    }

    @Test
    public void remainingCountsTheLimitAndTodaysBonus() {
        assertEquals(Long.MAX_VALUE, KidsMode.remaining(500 * MIN, 0, 0));
        assertEquals(20 * MIN, KidsMode.remaining(40 * MIN, 60, 0));
        assertEquals(0, KidsMode.remaining(75 * MIN, 60, 0));
        assertEquals(15 * MIN, KidsMode.remaining(75 * MIN, 60, 30 * MIN));
    }

    @Test
    public void limitsOfferNoLimitFirst() {
        assertEquals(0, KidsMode.LIMITS[0]);
        for (int i = 1; i < KidsMode.LIMITS.length; i++) assertTrue(KidsMode.LIMITS[i] > KidsMode.LIMITS[i - 1]);
    }
}
