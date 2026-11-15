package io.github.abd3lraouf.adhan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;

/**
 * Plays the adhan once. It takes transient audio focus, so SmartTube, VLC and other players pause and pick up
 * again afterwards. A recording of your own in /sdcard/Adhan/ wins over the built-in one; Fajr looks for
 * fajr.mp3 first. If nothing plays, a short tone sounds instead.
 */
public final class AdhanService extends Service implements AudioManager.OnAudioFocusChangeListener {
    static final String ACTION_STOP = "io.github.abd3lraouf.adhan.STOP";
    static final String CHANNEL = "adhan_playing";
    static final int NOTE = 1;
    /** A long recording of your own is cut here. */
    static final long MAX_MS = 6 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private ToneGenerator tone;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private BroadcastReceiver screenOff;

    static void start(Context c, String key) {
        c.startForegroundService(new Intent(c, AdhanService.class).putExtra(Scheduler.EXTRA_KEY, key));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, AdhanService.class));
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String key = intent == null ? null : intent.getStringExtra(Scheduler.EXTRA_KEY);
        foreground(key);
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        release();
        audio = getSystemService(AudioManager.class);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(this, handler)
                .build();
        int got = audio.requestAudioFocus(focus);
        Log.i(Scheduler.TAG, "audio focus " + (got == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "granted" : "refused"));
        if (screenOff == null) {
            screenOff = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    stopSelf();
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenOff, f);
        }
        if (!play(own(key), attrs) && !play(null, attrs)) beep();
        handler.postDelayed(this::stopSelf, MAX_MS);
        return START_NOT_STICKY;
    }

    /** Your own recording for this prayer, or null. */
    private static File own(String key) {
        File dir = new File(Environment.getExternalStorageDirectory(), "Adhan");
        String[] names = "Fajr".equals(key)
                ? new String[]{"fajr.mp3", "fajr.m4a", "fajr.ogg", "adhan.mp3", "adhan.m4a", "adhan.ogg"}
                : new String[]{"adhan.mp3", "adhan.m4a", "adhan.ogg"};
        for (String n : names) {
            File f = new File(dir, n);
            try {
                if (f.canRead() && f.length() > 0) return f;
            } catch (SecurityException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Plays {@code file}, or the built-in recording when it is null. False when it cannot start. */
    private boolean play(File file, AudioAttributes attrs) {
        releasePlayer();
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            if (file != null) {
                mp.setDataSource(file.getPath());
            } else {
                try (AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.adhan)) {
                    mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                }
            }
            float v = Settings.volume(this) / 100f;
            mp.setVolume(v, v);
            mp.setOnCompletionListener(m -> stopSelf());
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(Scheduler.TAG, "playback error " + what + "/" + extra);
                releasePlayer();
                if (file == null || !play(null, attrs)) beep();
                return true;
            });
            mp.prepare();
            mp.start();
            player = mp;
            Log.i(Scheduler.TAG, "playing " + (file == null ? "built-in adhan" : file.getPath()));
            return true;
        } catch (Exception e) {
            Log.w(Scheduler.TAG, "cannot play " + (file == null ? "built-in adhan" : file.getPath()) + ": " + e);
            mp.release();
            return false;
        }
    }

    /** The last resort: three short tones. */
    private void beep() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, Settings.volume(this));
            for (int i = 0; i < 3; i++) {
                handler.postDelayed(() -> {
                    if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600);
                }, i * 1_000L);
            }
        } catch (RuntimeException e) {
            Log.w(Scheduler.TAG, "no tone: " + e);
        }
        handler.postDelayed(this::stopSelf, 3_500);
    }

    @Override public void onAudioFocusChange(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS) stopSelf();
    }

    private void foreground(String key) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_playing),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 4,
                new Intent(this, AdhanService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.note)
                .setContentTitle(getString(R.string.playing, key == null ? "" : Settings.label(this, key)))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTE, n);
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
                // never started
            }
            player.release();
            player = null;
        }
    }

    private void release() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (tone != null) {
            tone.release();
            tone = null;
        }
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
        focus = null;
    }

    @Override public void onDestroy() {
        release();
        if (screenOff != null) {
            unregisterReceiver(screenOff);
            screenOff = null;
        }
        stopForeground(true);
        super.onDestroy();
    }
}
