package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * The Quran pill (quran_tile_package in config.xml). Quran Reader TV has no intent that starts recitation, but its
 * media session survives a trip Home paused, and the system sends a dispatched media key to the session that
 * played last. So when Quran is the last app opened from Home, one press pauses or resumes it without leaving
 * Home; otherwise the press opens the app, where OK on a verse starts the reciter.
 */
final class QuranTile {
    static final int OPEN = 0, PLAY = 1, PAUSE = 2;
    private static final String PREFS = "quran_tile", LAST = "last_opened_package";

    /** What a press does: only Quran's own session may get the key, or another app would start playing. */
    static int action(boolean quranWasLast, boolean musicActive) {
        if (!quranWasLast) return OPEN;
        return musicActive ? PAUSE : PLAY;
    }

    /** Home opened {@code pkg}: remembered so a media key only goes out while Quran is the app that last played. */
    static void opened(Context ctx, String pkg) {
        if (pkg == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST, pkg).apply();
    }

    static boolean wasLast(Context ctx, String quranPackage) {
        return quranPackage.equals(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST, null));
    }

    static boolean musicActive(Context ctx) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        return am != null && am.isMusicActive();
    }

    /** A press and release of {@code keyCode}, to the session that holds the media buttons. */
    static void sendKey(Context ctx, int keyCode) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    /** The app's TV launch intent, or its phone one; null when it is not installed. */
    static Intent launchIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    private QuranTile() {
    }
}
