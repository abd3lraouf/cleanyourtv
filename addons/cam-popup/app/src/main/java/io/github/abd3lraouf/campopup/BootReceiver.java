package io.github.abd3lraouf.campopup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the watcher at boot and after an update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Log.i(Settings.TAG, "start on " + intent.getAction());
        CamService.start(context);
    }
}
