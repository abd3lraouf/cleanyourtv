package io.github.abd3lraouf.adhan;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Date;

/** The next adhan, and the choices: sound, which prayers, how loud. Opening it once lets boot events reach the app. */
public final class StatusActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);

        CheckBox sound = findViewById(R.id.sound);
        sound.setChecked(Settings.sound(this));
        sound.setOnCheckedChangeListener((b, on) -> Settings.setSound(this, on));

        LinearLayout prayers = findViewById(R.id.prayers);
        for (String key : Schedule.FIVE) {
            CheckBox box = (CheckBox) getLayoutInflater().inflate(R.layout.prayer_box, prayers, false);
            box.setText(Settings.label(this, key));
            box.setChecked(Settings.enabled(this, key));
            box.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
                Settings.setEnabled(this, key, on);
                refresh();
            });
            prayers.addView(box);
        }

        SeekBar volume = findViewById(R.id.volume);
        volume.setMax(Settings.VOLUME_MAX - Settings.VOLUME_MIN);
        volume.setKeyProgressIncrement(10);
        volume.setProgress(Settings.volume(this) - Settings.VOLUME_MIN);
        showVolume();
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                Settings.setVolume(StatusActivity.this, p + Settings.VOLUME_MIN);
                showVolume();
            }

            @Override public void onStartTrackingTouch(SeekBar s) {}

            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        findViewById(R.id.test).setOnClickListener(v -> {
            Scheduler.State s = Scheduler.load(this, System.currentTimeMillis());
            String key = s.next != null ? s.next.key : "Dhuhr";
            Adhan.ring(this, key, s.next != null ? s.next.minutes : -1);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void showVolume() {
        ((TextView) findViewById(R.id.volume_label)).setText(getString(R.string.volume, Settings.volume(this)));
    }

    private void refresh() {
        Scheduler.State s = Scheduler.reschedule(this);
        String text;
        if (s.next == null) {
            text = getString(R.string.no_times);
        } else {
            String when = DateFormat.getTimeFormat(this).format(new Date(s.next.at));
            text = getString(R.string.next, Settings.label(this, s.next.key), when);
            if (!s.live) text += "\n" + getString(R.string.kept_times);
        }
        if (!android.provider.Settings.canDrawOverlays(this)) text += "\n" + getString(R.string.no_overlay);
        ((TextView) findViewById(R.id.status)).setText(text);
    }
}
