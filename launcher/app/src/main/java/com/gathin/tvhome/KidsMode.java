package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.EditText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Kids mode (kids_mode in config.xml): Home shows only the apps a parent picked, everything else (pills, library,
 * settings, tile menu) sits behind a PIN, and an optional daily time limit ends the day with a PIN-locked screen.
 *
 * Time is counted without a service: one open "segment" runs from when kids mode is on and the screen is on, and
 * Home folds it into the day's total whenever it resumes, pauses, launches an app or the screen goes off. The
 * static methods hold the PIN and budget arithmetic so they can be unit tested; an instance reads/writes the prefs.
 */
final class KidsMode {
    static final String PREFS = "kids_mode";
    private static final String ON = "on", PIN = "pin", LIMIT = "limit_min", APPS = "apps", DAY = "day",
            USED = "used_ms", SEGMENT = "segment_start", BONUS = "bonus_ms", BONUS_DAY = "bonus_day";
    /** Daily limits offered in Launcher settings, minutes; 0 is no limit. */
    static final int[] LIMITS = {0, 30, 60, 90, 120};
    static final int MIN_PIN = 4, MAX_PIN = 8;
    /** One segment never counts more than this: a clock jump or a missed screen-off must not eat days. */
    static final long MAX_SEGMENT_MS = 6 * 60 * 60 * 1000L;
    /** What the PIN on the time-up screen can add for today. */
    static final long BONUS_STEP_MS = 30 * 60 * 1000L;

    // ---- pure logic (unit tested) ----

    static boolean validPin(String pin) {
        if (pin == null || pin.length() < MIN_PIN || pin.length() > MAX_PIN) return false;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** "salt$sha256(salt:pin)" in hex; the PIN itself is never stored. */
    static String hashPin(String pin, String salt) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(salt).append('$');
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String newSalt(SecureRandom random) {
        byte[] b = new byte[8];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static boolean pinMatches(String stored, String pin) {
        if (stored == null || pin == null) return false;
        int sep = stored.indexOf('$');
        if (sep <= 0) return false;
        byte[] expected = stored.getBytes(StandardCharsets.UTF_8);
        byte[] actual = hashPin(pin, stored.substring(0, sep)).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    static long epochDay(long millis, ZoneId zone) {
        return java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay();
    }

    /**
     * Folds the segment [start, now) into the total of {@code day}. Returns {today, usedToday}: a new day starts
     * from zero (midnight reset) and only the part of the segment after that midnight counts. start 0 = no segment.
     */
    static long[] fold(long day, long used, long start, long now, ZoneId zone) {
        long today = epochDay(now, zone);
        if (today != day) used = 0;
        if (start <= 0 || now <= start) return new long[]{today, used};
        long midnight = LocalDate.ofEpochDay(today).atStartOfDay(zone).toInstant().toEpochMilli();
        long from = Math.max(start, midnight);
        long add = Math.min(Math.max(0, now - from), MAX_SEGMENT_MS);
        return new long[]{today, used + add};
    }

    /** Milliseconds left today; Long.MAX_VALUE without a limit. */
    static long remaining(long usedToday, int limitMinutes, long bonusToday) {
        if (limitMinutes <= 0) return Long.MAX_VALUE;
        return Math.max(0, limitMinutes * 60_000L + bonusToday - usedToday);
    }

    // ---- stored state ----

    private final SharedPreferences prefs;

    KidsMode(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean on() { return prefs.getBoolean(ON, false); }

    boolean hasPin() { return prefs.getString(PIN, null) != null; }

    boolean checkPin(String pin) { return pinMatches(prefs.getString(PIN, null), pin); }

    void setPin(String pin) {
        prefs.edit().putString(PIN, hashPin(pin, newSalt(new SecureRandom()))).apply();
    }

    int limitMinutes() { return prefs.getInt(LIMIT, 0); }

    void setLimitMinutes(int minutes) { prefs.edit().putInt(LIMIT, minutes).apply(); }

    Set<String> apps() {
        return Collections.unmodifiableSet(new HashSet<>(prefs.getStringSet(APPS, Collections.emptySet())));
    }

    void setApps(Set<String> apps) { prefs.edit().putStringSet(APPS, new HashSet<>(apps)).apply(); }

    void setOn(boolean on, long now) {
        if (on) {
            prefs.edit().putBoolean(ON, true).apply();
            startSegment(now);
        } else {
            pauseSegment(now);
            prefs.edit().putBoolean(ON, false).apply();
        }
    }

    /** The screen is on with kids mode on: count from now unless a segment is already open. */
    void startSegment(long now) {
        if (prefs.getLong(SEGMENT, 0) <= 0) prefs.edit().putLong(SEGMENT, now).apply();
    }

    /** Fold the open segment into today's total and keep it running from now. */
    void checkpoint(long now) {
        long start = prefs.getLong(SEGMENT, 0);
        long[] r = fold(prefs.getLong(DAY, 0), prefs.getLong(USED, 0), start, now, ZoneId.systemDefault());
        SharedPreferences.Editor e = prefs.edit().putLong(DAY, r[0]).putLong(USED, r[1]);
        if (start > 0) e.putLong(SEGMENT, now);
        e.apply();
    }

    /** Screen off or kids mode off: fold and stop counting. */
    void pauseSegment(long now) {
        checkpoint(now);
        prefs.edit().putLong(SEGMENT, 0).apply();
    }

    long usedToday(long now) {
        long[] r = fold(prefs.getLong(DAY, 0), prefs.getLong(USED, 0), prefs.getLong(SEGMENT, 0), now,
                ZoneId.systemDefault());
        return r[1];
    }

    long remaining(long now) {
        long today = epochDay(now, ZoneId.systemDefault());
        long bonus = prefs.getLong(BONUS_DAY, -1) == today ? prefs.getLong(BONUS, 0) : 0;
        return remaining(usedToday(now), limitMinutes(), bonus);
    }

    boolean timeUp(long now) { return on() && limitMinutes() > 0 && remaining(now) <= 0; }

    /** The parent's PIN on the time-up screen: more time, today only. */
    void addBonus(long ms, long now) {
        long today = epochDay(now, ZoneId.systemDefault());
        long bonus = prefs.getLong(BONUS_DAY, -1) == today ? prefs.getLong(BONUS, 0) : 0;
        // Top up from what was used, so the extra time is real even when the day ran well past the limit.
        long over = Math.max(0, usedToday(now) - (limitMinutes() * 60_000L + bonus));
        prefs.edit().putLong(BONUS_DAY, today).putLong(BONUS, bonus + over + ms).apply();
    }

    // ---- PIN prompt ----

    /** A number-only PIN field in a dialog; {@code done} gets what was typed (validity is the caller's call). */
    static void askPin(Activity activity, int title, Consumer<String> done) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_PIN)});
        field.setGravity(Gravity.CENTER);
        field.setTextSize(28);
        field.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> done.accept(field.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        field.setOnEditorActionListener((v, actionId, event) -> {
            dialog.dismiss();
            done.accept(field.getText().toString());
            return true;
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        field.requestFocus();
    }

    /** Asks for a new PIN twice; {@code done} runs only when both match and are 4-8 digits. */
    static void askNewPin(Activity activity, KidsMode kids, Runnable done) {
        askPin(activity, R.string.kids_pin_new, first -> {
            if (!validPin(first)) {
                android.widget.Toast.makeText(activity, R.string.kids_pin_invalid, android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            askPin(activity, R.string.kids_pin_confirm, second -> {
                if (!first.equals(second)) {
                    android.widget.Toast.makeText(activity, R.string.kids_pin_mismatch, android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                kids.setPin(first);
                done.run();
            });
        });
    }

    /** Asks for the parent's PIN; {@code ok} runs on a match, a wrong PIN says so. */
    static void verify(Activity activity, KidsMode kids, Runnable ok) {
        askPin(activity, R.string.kids_pin_enter, pin -> {
            if (kids.checkPin(pin)) ok.run();
            else android.widget.Toast.makeText(activity, R.string.kids_pin_wrong, android.widget.Toast.LENGTH_SHORT).show();
        });
    }
}
