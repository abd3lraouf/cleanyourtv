package io.github.abd3lraouf.adhan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;
import java.util.TimeZone;

/** The prayer-time alarm and the after-midnight look for new times. */
public final class AlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (!Scheduler.ACTION_FIRE.equals(action)) {
            Scheduler.reschedule(c);
            return;
        }
        long now = System.currentTimeMillis();
        String key = intent.getStringExtra(Scheduler.EXTRA_KEY);
        int minutes = intent.getIntExtra(Scheduler.EXTRA_MINUTES, -1);
        long at = intent.getLongExtra(Scheduler.EXTRA_AT, 0);
        boolean ring = key != null && Schedule.isFive(key) && Settings.enabled(c, key);
        if (ring && Schedule.late(at, now)) {
            Log.i(Scheduler.TAG, key + " alarm arrived " + (now - at) / 1000 + " s late: not rung");
            ring = false;
        }
        if (ring) {
            // The time may have moved since the alarm was set (Prayer Times fetched new times): go by the new one.
            List<Schedule.Row> live = Scheduler.query(c, null);
            TimeZone tz = TimeZone.getDefault();
            for (Schedule.Row r : live) {
                if (!key.equals(r.key)) continue;
                long t = Schedule.at(r.day, r.minutes, tz);
                if (t - now > 60_000L) {
                    Log.i(Scheduler.TAG, key + " moved to " + r.minutes + ": rescheduled");
                    ring = false;
                } else {
                    minutes = r.minutes;
                }
            }
        }
        String stamp = Schedule.day(now, TimeZone.getDefault()) + " " + key;
        if (ring && stamp.equals(Settings.prefs(c).getString("last_stamp", ""))) ring = false;
        Scheduler.reschedule(c);
        if (!ring) return;
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            Log.i(Scheduler.TAG, key + ": screen off, not rung");
            return;
        }
        Settings.prefs(c).edit().putString("last_stamp", stamp).apply();
        Adhan.ring(c, key, minutes);
    }
}
