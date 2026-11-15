package io.github.abd3lraouf.adhan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Shows the notice and starts the sound. */
final class Adhan {
    static final String CHANNEL_ALERT = "prayer_time";
    static final int NOTE_ALERT = 2;

    private Adhan() {}

    static void ring(Context c, String key, int minutes) {
        Log.i(Scheduler.TAG, "adhan " + key);
        Settings.prefs(c).edit().putString("last_key", key).putLong("last_at", System.currentTimeMillis()).apply();
        if (Settings.sound(c)) AdhanService.start(c, key);
        Intent notice = new Intent(c, AdhanActivity.class)
                .putExtra(Scheduler.EXTRA_KEY, key)
                .putExtra(Scheduler.EXTRA_MINUTES, minutes)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (android.provider.Settings.canDrawOverlays(c)) {
            // Allowed to draw over other apps, so Android lets it open a screen from the background.
            c.startActivity(notice);
            return;
        }
        // Without that grant, a full-screen-intent notification is the only way in.
        Log.w(Scheduler.TAG, "no overlay grant: full-screen notification instead");
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, c.getString(R.string.channel_alert),
                NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(c, 3, notice,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.note)
                .setContentTitle(c.getString(R.string.notice_title))
                .setContentText(Settings.label(c, key))
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(AdhanActivity.SHOW_MS)
                .build();
        nm.notify(NOTE_ALERT, n);
    }
}
