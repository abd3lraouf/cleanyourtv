package io.github.abd3lraouf.adhan;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Reads today's times from the Prayer Times add-on and sets one exact alarm for the next adhan, plus one alarm
 * to look again after midnight. Called at boot, on clock and time-zone changes, after every alarm, when the
 * provider reports new times and when a setting changes.
 */
final class Scheduler {
    static final String TAG = "Adhan";
    /** Prayer Times' provider, the same one the launcher's prayer panel reads. */
    static final Uri TIMES = Uri.parse("content://com.majnioui.tv.prayer.times/today");
    static final String ACTION_FIRE = "io.github.abd3lraouf.adhan.FIRE";
    static final String ACTION_REFRESH = "io.github.abd3lraouf.adhan.REFRESH";
    static final String EXTRA_KEY = "key";
    static final String EXTRA_MINUTES = "minutes";
    static final String EXTRA_AT = "at";
    private static final int JOB_TIMES = 1;

    private Scheduler() {}

    /** Today's rows straight from Prayer Times, or an empty list when it has none for today or is not installed. */
    static List<Schedule.Row> query(Context c, String[] placeOut) {
        List<Schedule.Row> rows = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(TIMES, null, null, null, null)) {
            if (cur == null) return rows;
            int ik = cur.getColumnIndex("key");
            int im = cur.getColumnIndex("minutes");
            int id = cur.getColumnIndex("day");
            int ip = cur.getColumnIndex("place");
            if (ik < 0 || im < 0 || id < 0) return rows;
            while (cur.moveToNext()) {
                rows.add(new Schedule.Row(cur.getString(ik), cur.getInt(im), cur.getString(id)));
                if (ip >= 0 && placeOut != null && placeOut[0] == null) placeOut[0] = cur.getString(ip);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Prayer Times not readable: " + e);
        }
        return rows;
    }

    /** What the next alarm is, for the status screen. */
    static final class State {
        List<Schedule.Row> rows;
        boolean live;
        String day;
        String place;
        Schedule.Slot next;
    }

    /** Today's times: live ones when Prayer Times has them, else the last ones kept, moved to today. */
    static State load(Context c, long now) {
        TimeZone tz = TimeZone.getDefault();
        String today = Schedule.day(now, tz);
        SharedPreferences p = Settings.prefs(c);
        String[] place = new String[1];
        List<Schedule.Row> rows = query(c, place);
        State s = new State();
        if (!rows.isEmpty() && today.equals(rows.get(0).day)) {
            s.live = true;
            s.day = today;
            s.place = place[0];
            p.edit().putString("rows", Schedule.encode(rows)).putString("rows_day", today)
                    .putString("place", place[0]).apply();
        } else {
            String cachedDay = p.getString("rows_day", "");
            s.day = cachedDay;
            s.place = p.getString("place", null);
            rows = Schedule.carryOver(Schedule.decode(p.getString("rows", ""), cachedDay), cachedDay, today);
        }
        s.rows = rows;
        s.next = Schedule.next(rows, now, Settings.disabled(c), tz);
        if (s.next == null && !rows.isEmpty()) {
            // After Isha: tomorrow's Fajr, from today's times. The refresh after midnight puts the real one in.
            String tomorrow = Schedule.day(Schedule.refreshAt(now, tz), tz);
            s.next = Schedule.next(Schedule.carryOver(rows, today, tomorrow), now, Settings.disabled(c), tz);
        }
        return s;
    }

    static State reschedule(Context c) {
        long now = System.currentTimeMillis();
        State s = load(c, now);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent fire = pending(c, ACTION_FIRE, 1, s.next);
        if (s.next != null) {
            setExact(am, s.next.at, fire);
            Settings.prefs(c).edit().putString("next_key", s.next.key).putLong("next_at", s.next.at).apply();
            Log.i(TAG, "next adhan " + s.next.key + " at " + new java.util.Date(s.next.at) + (s.live ? "" : " (kept times)"));
        } else {
            am.cancel(fire);
            Settings.prefs(c).edit().remove("next_key").remove("next_at").apply();
            Log.i(TAG, "no prayer times: nothing scheduled");
        }
        long refresh = s.live ? Schedule.refreshAt(now, TimeZone.getDefault()) : now + Schedule.RETRY_MS;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, refresh, pending(c, ACTION_REFRESH, 2, null));
        watchTimes(c);
        return s;
    }

    private static void setExact(AlarmManager am, long at, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static PendingIntent pending(Context c, String action, int code, Schedule.Slot slot) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action);
        if (slot != null) {
            i.putExtra(EXTRA_KEY, slot.key).putExtra(EXTRA_MINUTES, slot.minutes).putExtra(EXTRA_AT, slot.at);
        }
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** A one-shot job that runs when Prayer Times announces new times (it calls notifyChange on its URI). */
    static void watchTimes(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo job = new JobInfo.Builder(JOB_TIMES, new ComponentName(c, DataJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(TIMES,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(2_000)
                .setTriggerContentMaxDelay(30_000)
                .build();
        try {
            js.schedule(job);
        } catch (RuntimeException e) {
            Log.w(TAG, "content watch not scheduled: " + e);
        }
    }
}
