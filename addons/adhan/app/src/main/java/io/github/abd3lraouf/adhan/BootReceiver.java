package io.github.abd3lraouf.adhan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms do not survive a reboot, an update or a clock change: set the next one again. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        Scheduler.reschedule(c);
    }
}
