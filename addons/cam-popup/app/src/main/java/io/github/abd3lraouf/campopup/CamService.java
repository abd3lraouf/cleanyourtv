package io.github.abd3lraouf.campopup;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Runs from boot: listens to the recorder's motion events ({@link OnvifEvents}), asks {@link PopupPolicy} what
 * to do, and opens or closes the {@link Popup}. A foreground service so Android keeps it alive; its
 * notification channel is low importance.
 *
 * <p>The test broadcast, from ADB only (the sender needs WRITE_SECURE_SETTINGS, which the shell holds):
 * {@code am broadcast -a io.github.abd3lraouf.campopup.TEST --ei channel 1}, optionally with
 * {@code --es url rtsp://...} to play another stream in the same window.
 */
public final class CamService extends Service {
    static final String ACTION_TEST = "io.github.abd3lraouf.campopup.TEST";
    static final String ACTION_RELOAD = "io.github.abd3lraouf.campopup.RELOAD";
    static final String STATUS = "status";
    static final String STATUS_AT = "status_at";
    static final String LAST_MOTION = "last_motion";
    static final String LAST_MOTION_AT = "last_motion_at";

    private static final String CHANNEL = "watch";
    private static final int NOTIFICATION = 1;
    private static final long MAX_ACTIVE_MS = 120_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Settings settings;
    private PopupPolicy policy;
    private Popup popup;
    private OnvifEvents events;
    private String overrideUrl;
    private boolean registered;

    static void start(Context context) {
        context.startForegroundService(new Intent(context, CamService.class));
    }

    static void reload(Context context) {
        context.startForegroundService(new Intent(context, CamService.class).setAction(ACTION_RELOAD));
    }

    private final Runnable closer = this::checkClose;

    private final BroadcastReceiver test = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int ch = intent.getIntExtra("channel", 1);
            String url = intent.getStringExtra("url");
            Log.i(Settings.TAG, "test pop-up, channel " + ch + (url == null ? "" : " (url override)"));
            overrideUrl = url;
            apply(ch, policy.force(ch, SystemClock.elapsedRealtime()));
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")   // before 33 the permission alone guards it
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel),
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification))
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, n);
        }
        popup = new Popup(this, new Popup.Listener() {
            @Override public void onDismiss() {
                closeNow();
            }

            @Override public void onFullScreen(int channel) {
                closeNow();
                startActivity(new Intent(CamService.this, LiveActivity.class)
                        .putExtra(LiveActivity.EXTRA_CHANNEL, channel)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        IntentFilter f = new IntentFilter(ACTION_TEST);
        String perm = android.Manifest.permission.WRITE_SECURE_SETTINGS;
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(test, f, perm, main, Context.RECEIVER_EXPORTED);
        else registerReceiver(test, f, perm, main);
        registered = true;
        configure();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) configure();
        return START_STICKY;
    }

    /** (Re)reads the settings and restarts the event listener when the recorder or login changed. */
    private void configure() {
        Settings old = settings;
        settings = Settings.load(this);
        policy = new PopupPolicy(settings.holdSeconds * 1000L, settings.cooldownSeconds * 1000L, MAX_ACTIVE_MS);
        if (popup.showing()) closeNow();
        boolean same = old != null && old.host.equals(settings.host) && old.user.equals(settings.user)
                && old.password.equals(settings.password);
        if (same && events != null) return;
        if (events != null) events.stop();
        events = null;
        if (!settings.configured()) {
            note(getString(R.string.not_configured));
            return;
        }
        events = new OnvifEvents(settings.host, settings.user, settings.password, new OnvifEvents.Sink() {
            @Override public void motion(int channel, boolean active) {
                main.post(() -> onMotion(channel, active));
            }

            @Override public void status(String text) {
                main.post(() -> note(text));
            }
        });
        note(getString(R.string.connecting));
        events.start();
    }

    private void onMotion(int channel, boolean active) {
        long now = SystemClock.elapsedRealtime();
        if (active) {
            Settings.prefs(this).edit().putInt(LAST_MOTION, channel)
                    .putLong(LAST_MOTION_AT, System.currentTimeMillis()).apply();
        }
        apply(channel, policy.onMotion(channel, active, now, settings.allows(channel)));
    }

    private void apply(int channel, PopupPolicy.Action action) {
        switch (action) {
            case SHOW:
            case SWITCH:
                if (LiveActivity.onScreen && overrideUrl == null) {   // the cameras are already full screen
                    policy.closed(SystemClock.elapsedRealtime());
                    return;
                }
                String url = overrideUrl != null ? overrideUrl : settings.rtspUrl(channel);
                if (!popup.show(channel, url)) {
                    policy.closed(SystemClock.elapsedRealtime());
                    note(getString(R.string.no_overlay));
                    return;
                }
                break;
            case EXTEND:
                if (overrideUrl != null) popup.show(channel, overrideUrl);
                break;
            default:
                return;
        }
        scheduleClose();
    }

    private void scheduleClose() {
        main.removeCallbacks(closer);
        long at = policy.closeAt();
        if (at != 0) main.postAtTime(closer, at);
    }

    private void checkClose() {
        if (policy.due(SystemClock.elapsedRealtime())) closeNow();
        else scheduleClose();
    }

    private void closeNow() {
        main.removeCallbacks(closer);
        policy.closed(SystemClock.elapsedRealtime());
        popup.close();
        overrideUrl = null;
    }

    private void note(String text) {
        Settings.prefs(this).edit().putString(STATUS, text).putLong(STATUS_AT, System.currentTimeMillis()).apply();
    }

    @Override public void onDestroy() {
        if (events != null) events.stop();
        if (registered) unregisterReceiver(test);
        popup.close();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
