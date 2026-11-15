package io.github.abd3lraouf.campopup;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The cameras, live, full screen: a grid of the chosen cameras' sub-streams. OK on a camera opens it alone on
 * its full-resolution stream, left and right step to the other cameras, Back returns to the grid. Menu opens
 * the settings. Only the pictures on screen are streamed, so the recorder and the TV's decoder carry no more
 * than they show.
 */
public final class LiveActivity extends Activity {
    /** True while the live view is on screen: the corner pop-up would only repeat a camera already shown. */
    static volatile boolean onScreen;

    private Settings settings;
    private final List<Integer> channels = new ArrayList<>();
    private final List<StreamView> tiles = new ArrayList<>();
    private FrameLayout root;
    private LinearLayout grid;
    private StreamView full;
    private TextView hint;
    private int fullIndex = -1;
    private int focused;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        CamService.start(this);
    }

    @Override protected void onStart() {
        super.onStart();
        onScreen = true;
        settings = Settings.load(this);
        channels.clear();
        channels.addAll(settings.view);
        build();
        if (fullIndex >= 0 && fullIndex < channels.size()) showFull(fullIndex);
        else showGrid();
    }

    @Override protected void onStop() {
        onScreen = false;
        stopAll();
        super.onStop();
    }

    private void build() {
        root.removeAllViews();
        tiles.clear();
        float dp = getResources().getDisplayMetrics().density;
        if (!settings.configured() || channels.isEmpty()) {
            TextView t = new TextView(this);
            t.setTextColor(0xCCFFFFFF);
            t.setTextSize(20);
            t.setText(R.string.not_configured);
            root.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            return;
        }
        int cols = LiveGrid.columns(channels.size());
        int rows = LiveGrid.rows(channels.size());
        int gap = Math.round(4 * dp);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(gap, gap, gap, gap);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                FrameLayout cell = new FrameLayout(this);
                cell.setPadding(gap, gap, gap, gap);
                if (i < channels.size()) {
                    final int index = i;
                    StreamView v = new StreamView(this, -1);
                    v.setLabel(getString(R.string.popup_label, channels.get(i)));
                    cell.addView(v, new FrameLayout.LayoutParams(-1, -1));
                    cell.setFocusable(true);
                    cell.setOnFocusChangeListener((view, has) -> {
                        view.setBackground(has ? frame(dp) : null);
                        if (has) focused = index;
                    });
                    cell.setOnClickListener(view -> showFull(index));
                    tiles.add(v);
                }
                row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
            }
            grid.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        root.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        full = new StreamView(this, -1);
        full.setVisibility(View.GONE);
        root.addView(full, new FrameLayout.LayoutParams(-1, -1));

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        int pad = Math.round(10 * dp);
        hint.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xAA000000);
        bg.setCornerRadius(20 * dp);
        hint.setBackground(bg);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = Math.round(24 * dp);
        root.addView(hint, hl);
    }

    private static GradientDrawable frame(float dp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(6 * dp);
        d.setStroke(Math.round(3 * dp), Color.WHITE);
        return d;
    }

    private void showGrid() {
        if (grid == null) return;
        fullIndex = -1;
        full.stop();
        full.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        for (int i = 0; i < tiles.size(); i++) tiles.get(i).play(settings.rtspUrl(channels.get(i), false));
        View cell = (View) tiles.get(Math.min(focused, tiles.size() - 1)).getParent();
        cell.requestFocus();
        flashHint(R.string.live_hint_grid);
    }

    private void showFull(int index) {
        if (grid == null) return;
        fullIndex = index;
        focused = index;
        for (StreamView t : tiles) t.stop();   // free the decoder and the recorder's sessions for the big picture
        grid.setVisibility(View.GONE);
        full.setVisibility(View.VISIBLE);
        int ch = channels.get(index);
        full.setLabel(getString(R.string.popup_label, ch));
        full.play(settings.rtspUrl(ch, true));
        flashHint(channels.size() > 1 ? R.string.live_hint_full : R.string.live_hint_single);
    }

    private void flashHint(int text) {
        hint.setText(text);
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(4_000).setDuration(600).start();
    }

    private void stopAll() {
        for (StreamView t : tiles) t.stop();
        if (full != null) full.stop();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, StatusActivity.class));
            return true;
        }
        if (fullIndex >= 0) {
            int n = channels.size();
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    if (n > 1) showFull((fullIndex + 1) % n);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (n > 1) showFull((fullIndex + n - 1) % n);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    flashHint(n > 1 ? R.string.live_hint_full : R.string.live_hint_single);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (fullIndex >= 0 && channels.size() > 1) {
            showGrid();
            return;
        }
        super.onBackPressed();
    }
}
