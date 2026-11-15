package io.github.abd3lraouf.campopup;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;

/**
 * The picture-in-picture window: a quarter-width 16:9 box in the bottom corner, drawn over everything as an
 * application overlay that never takes focus or touches, so the remote keeps driving whatever is on screen.
 * Plays one channel through a {@link StreamView}. Main thread only.
 */
final class Popup {
    private static final int MAX_RETRIES = 3;

    private final Context context;
    private final WindowManager wm;
    private StreamView root;

    Popup(Context context) {
        this.context = context;
        this.wm = context.getSystemService(WindowManager.class);
    }

    boolean showing() {
        return root != null;
    }

    /** Opens the window, or retunes it, on this stream. Returns false if Android refused the overlay. */
    boolean show(int channel, String rtspUrl) {
        if (root == null && !open()) return false;
        root.setLabel(context.getString(R.string.popup_label, channel));
        root.play(rtspUrl);
        return true;
    }

    void close() {
        if (root != null) {
            root.stop();
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
                // already gone
            }
            root = null;
        }
    }

    @SuppressLint("RtlHardcoded")   // the same screen corner in Arabic and English: it is a picture, not text
    private boolean open() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels) / 4;   // 480 on a 1920x1080 panel
        int h = w * 9 / 16;
        float dp = dm.density;
        int margin = Math.round(24 * dp);

        root = new StreamView(context, MAX_RETRIES);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(12 * dp);
        bg.setStroke(Math.max(1, Math.round(dp)), 0x40FFFFFF);
        root.setBackground(bg);
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 12 * dp);
            }
        });
        root.setClipToOutline(true);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        p.x = margin;
        p.y = margin;
        p.setTitle("Cam Popup");
        try {
            wm.addView(root, p);
        } catch (RuntimeException e) {
            Log.w(Settings.TAG, "overlay refused (appops SYSTEM_ALERT_WINDOW?): " + e);
            root = null;
            return false;
        }
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(200).start();
        return true;
    }
}
