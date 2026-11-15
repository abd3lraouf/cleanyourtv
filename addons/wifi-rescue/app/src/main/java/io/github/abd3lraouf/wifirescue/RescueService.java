package io.github.abd3lraouf.wifirescue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Watches the Wi-Fi network and runs the {@link Rescue} table: re-check requests first, then a Wi-Fi off/on.
 * A foreground service so Android keeps it alive from boot; the notification channel is low importance.
 */
public final class RescueService extends Service {
    static final String TAG = "WifiRescue";
    static final String PREFS = "status";
    private static final String CHANNEL = "watch";
    private static final int NOTIFICATION = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Rescue rescue = new Rescue();
    private ConnectivityManager cm;
    private WifiManager wifi;
    private Network wifiNetwork;
    private boolean registered;

    static void start(Context context) {
        Intent intent = new Intent(context, RescueService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private final Runnable tick = this::tick;

    private final Runnable switchOn = () -> {
        boolean ok = wifi.setWifiEnabled(true);
        note("Wi-Fi on (" + (ok ? "ok" : "refused") + ")");
    };

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            handler.post(() -> onWifi(network, caps));
        }

        @Override public void onLost(Network network) {
            handler.post(() -> {
                if (!network.equals(wifiNetwork)) return;
                wifiNetwork = null;
                rescue.lost();
                handler.removeCallbacks(tick);
                Log.i(TAG, "Wi-Fi lost");
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        cm = getSystemService(ConnectivityManager.class);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        startForeground(NOTIFICATION, notification());
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cm.registerNetworkCallback(request, callback);
        registered = true;
        prefs().edit().putLong("started", System.currentTimeMillis()).apply();
        note("watching Wi-Fi");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (registered) cm.unregisterNetworkCallback(callback);
        registered = false;
        Log.i(TAG, "stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void onWifi(Network network, NetworkCapabilities caps) {
        boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean changed = !network.equals(wifiNetwork);
        wifiNetwork = network;
        if (validated) {
            boolean wasWaiting = rescue.waiting() || rescue.toggles() > 0;
            rescue.validated();
            handler.removeCallbacks(tick);
            if (wasWaiting || changed) note("Wi-Fi validated");
            return;
        }
        if (rescue.waiting() && !changed) return;
        rescue.unvalidated(SystemClock.elapsedRealtime());
        Log.i(TAG, "Wi-Fi connected without internet, watching");
        schedule(Rescue.FIRST_REPORT_MS);
    }

    private void schedule(long delay) {
        handler.removeCallbacks(tick);
        if (delay >= 0) handler.postDelayed(tick, delay);
    }

    private void tick() {
        Rescue.Decision d = rescue.decide(SystemClock.elapsedRealtime());
        switch (d.action) {
            case REPORT:
                if (wifiNetwork != null) cm.reportNetworkConnectivity(wifiNetwork, false);
                note("re-check " + rescue.reports() + "/" + Rescue.MAX_REPORTS);
                break;
            case TOGGLE:
                boolean ok = wifi.setWifiEnabled(false);
                note("Wi-Fi off (" + (ok ? "ok" : "refused") + "), toggle " + rescue.toggles() + "/" + Rescue.MAX_TOGGLES);
                handler.postDelayed(switchOn, Rescue.OFF_FOR_MS);
                prefs().edit().putInt("toggles_total", prefs().getInt("toggles_total", 0) + 1).apply();
                break;
            default:
                if (rescue.gaveUp() && d.wakeInMs < 0) note("gave up after " + Rescue.MAX_TOGGLES + " toggles");
        }
        schedule(d.wakeInMs);
    }

    private void note(String what) {
        Log.i(TAG, what);
        prefs().edit().putString("last", what).putLong("last_at", System.currentTimeMillis()).apply();
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel),
                NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification))
                .setOngoing(true)
                .build();
    }
}
