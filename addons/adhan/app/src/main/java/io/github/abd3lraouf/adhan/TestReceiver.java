package io.github.abd3lraouf.adhan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Rings now, to try it:
 * {@code adb shell am broadcast -a io.github.abd3lraouf.adhan.TEST -p io.github.abd3lraouf.adhan [--es prayer Fajr]}.
 * The manifest lets only a sender holding DUMP (the adb shell) reach it.
 */
public final class TestReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String key = intent.getStringExtra("prayer");
        Scheduler.State s = Scheduler.load(c, System.currentTimeMillis());
        if (key == null) key = s.next != null ? s.next.key : "Dhuhr";
        int minutes = -1;
        for (Schedule.Row r : s.rows) if (key.equals(r.key)) minutes = r.minutes;
        Adhan.ring(c, key, minutes);
    }
}
