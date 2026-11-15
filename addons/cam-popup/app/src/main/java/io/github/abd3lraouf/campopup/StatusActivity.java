package io.github.abd3lraouf.campopup;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Button;
import android.widget.TextView;

import java.util.Date;
import java.util.TreeSet;

/**
 * Opened once after install so Android delivers BOOT_COMPLETED to the app. Shows the recorder link's state and
 * the last motion, and holds the quiet settings: pop-ups on or off, the per-camera cooldown, and which cameras
 * may pop up. The login itself is never shown here.
 */
public final class StatusActivity extends Activity {
    private Button toggle;
    private Button cooldown;
    private final Button[] cams = new Button[Settings.CHANNEL_COUNT];

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);
        toggle = findViewById(R.id.toggle);
        cooldown = findViewById(R.id.cooldown);
        int[] ids = {R.id.cam1, R.id.cam2, R.id.cam3, R.id.cam4, R.id.cam5, R.id.cam6, R.id.cam7, R.id.cam8};
        for (int i = 0; i < cams.length; i++) {
            final int ch = i + 1;
            cams[i] = findViewById(ids[i]);
            cams[i].setOnClickListener(v -> flipChannel(ch));
        }
        toggle.setOnClickListener(v -> {
            SharedPreferences p = Settings.prefs(this);
            p.edit().putBoolean(Settings.ENABLED, !p.getBoolean(Settings.ENABLED, true)).apply();
            changed();
        });
        cooldown.setOnClickListener(v -> {
            SharedPreferences p = Settings.prefs(this);
            p.edit().putInt(Settings.COOLDOWN,
                    Settings.nextCooldown(p.getInt(Settings.COOLDOWN, Settings.DEFAULT_COOLDOWN))).apply();
            changed();
        });
        CamService.start(this);
    }

    private void flipChannel(int ch) {
        SharedPreferences p = Settings.prefs(this);
        TreeSet<Integer> set = Settings.parseChannels(p.getString(Settings.CHANNELS, Settings.ALL_CHANNELS));
        if (!set.remove(ch)) set.add(ch);
        p.edit().putString(Settings.CHANNELS, Settings.formatChannels(set)).apply();
        changed();
    }

    private void changed() {
        CamService.reload(this);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        Settings s = Settings.load(this);
        SharedPreferences p = Settings.prefs(this);
        String link = p.getString(CamService.STATUS, getString(R.string.nothing_yet));
        int ch = p.getInt(CamService.LAST_MOTION, 0);
        long at = p.getLong(CamService.LAST_MOTION_AT, 0);
        String motion = ch == 0 ? getString(R.string.nothing_yet)
                : getString(R.string.popup_label, ch) + "  " + DateFormat.getTimeFormat(this).format(new Date(at));
        ((TextView) findViewById(R.id.status)).setText(getString(R.string.status,
                s.configured() ? s.host : getString(R.string.not_configured), link, motion));
        toggle.setText(s.enabled ? R.string.popups_on : R.string.popups_off);
        cooldown.setText(getString(R.string.cooldown, cooldownText(s.cooldownSeconds)));
        for (int i = 0; i < cams.length; i++) {
            cams[i].setText(String.valueOf(i + 1));
            cams[i].setAlpha(s.channels.contains(i + 1) ? 1f : 0.35f);
            cams[i].setContentDescription(getString(R.string.popup_label, i + 1));
        }
    }

    private String cooldownText(int seconds) {
        if (seconds == 0) return getString(R.string.cooldown_none);
        if (seconds < 60) return getString(R.string.seconds, seconds);
        return getString(R.string.minutes, seconds / 60);
    }
}
