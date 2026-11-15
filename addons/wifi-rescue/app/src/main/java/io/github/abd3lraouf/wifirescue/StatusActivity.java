package io.github.abd3lraouf.wifirescue;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.TextView;

import java.util.Date;

/** Opened once after install so Android delivers BOOT_COMPLETED to the app; shows what the watcher last did. */
public final class StatusActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_status);
        RescueService.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        SharedPreferences p = getSharedPreferences(RescueService.PREFS, MODE_PRIVATE);
        String last = p.getString("last", getString(R.string.nothing_yet));
        long at = p.getLong("last_at", 0);
        String when = at == 0 ? "" : DateFormat.getTimeFormat(this).format(new Date(at)) + "  ";
        ((TextView) findViewById(R.id.status)).setText(getString(R.string.status,
                when + last, p.getInt("toggles_total", 0)));
    }
}
