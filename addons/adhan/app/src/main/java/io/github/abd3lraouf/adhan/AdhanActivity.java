package io.github.abd3lraouf.adhan;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Date;
import java.util.TimeZone;

/** The notice over whatever is on screen: the prayer's name and time, large. OK or Back closes it and the sound. */
public final class AdhanActivity extends Activity {
    /** It closes by itself after this. */
    static final long SHOW_MS = 3 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable close = this::close;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_adhan);
        show(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        show(intent);
    }

    private void show(Intent intent) {
        String key = intent.getStringExtra(Scheduler.EXTRA_KEY);
        int minutes = intent.getIntExtra(Scheduler.EXTRA_MINUTES, -1);
        ((TextView) findViewById(R.id.name)).setText(key == null ? "" : Settings.label(this, key));
        String time = "";
        if (minutes >= 0) {
            long now = System.currentTimeMillis();
            long at = Schedule.at(Schedule.day(now, TimeZone.getDefault()), minutes, TimeZone.getDefault());
            time = DateFormat.getTimeFormat(this).format(new Date(at));
        }
        String place = Settings.prefs(this).getString("place", null);
        ((TextView) findViewById(R.id.time)).setText(place == null || place.isEmpty()
                ? time : getString(R.string.notice_time_place, time, place));
        handler.removeCallbacks(close);
        handler.postDelayed(close, SHOW_MS);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                close();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // No onUserLeaveHint: Prayer Times opens its own small card at the same minute, and Android counts that launch
    // as "user leaving", which would cut the adhan off. Home leaves the adhan playing; the notification can stop it.

    private void close() {
        AdhanService.stop(this);
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(close);
        super.onDestroy();
    }
}
