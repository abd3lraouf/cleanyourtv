package io.github.abd3lraouf.campopup;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The picture-in-picture window: a third-width 16:9 card in the bottom corner, drawn over everything as an
 * application overlay. It takes the remote while it is up, so it can be dismissed: two buttons under the
 * picture, Close (focused first, so a stray OK only dismisses it) and Full screen; Back closes too. Play,
 * pause and the other media keys are handed on to the app underneath, and volume never reaches a window.
 * Main thread only.
 */
final class Popup {
    interface Listener {
        /** The viewer dismissed the window. */
        void onDismiss();

        /** The viewer asked for this camera full screen. */
        void onFullScreen(int channel);
    }

    private static final int MAX_RETRIES = 3;

    private final Context context;
    private final WindowManager wm;
    private final Listener listener;
    private Card root;
    private StreamView stream;
    private int channel;

    Popup(Context context, Listener listener) {
        this.context = context;
        this.wm = context.getSystemService(WindowManager.class);
        this.listener = listener;
    }

    boolean showing() {
        return root != null;
    }

    /** Opens the window, or retunes it, on this stream. Returns false if Android refused the overlay. */
    boolean show(int channel, String rtspUrl) {
        if (root == null && !open()) return false;
        this.channel = channel;
        stream.setLabel(context.getString(R.string.popup_label, channel));
        stream.play(rtspUrl);
        return true;
    }

    void close() {
        if (root != null) {
            stream.stop();
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
                // already gone
            }
            root = null;
            stream = null;
        }
    }

    /** The window's root: routes the remote's keys. */
    private final class Card extends LinearLayout {
        View first;   // focused when the window gets the remote, so the lit button shows from the start

        Card(Context c) {
            super(c);
        }

        @Override public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus && findFocus() == null && first != null) first.requestFocus();
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            int code = event.getKeyCode();
            if (isMediaKey(code)) {   // play/pause and friends belong to the app underneath
                context.getSystemService(AudioManager.class).dispatchMediaKeyEvent(event);
                return true;
            }
            switch (code) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) listener.onDismiss();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    return super.dispatchKeyEvent(event);   // move between and press the two buttons
                default:
                    return true;   // up, down, numbers…: swallowed while the card is up
            }
        }
    }

    private static boolean isMediaKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return true;
            default:
                return false;
        }
    }

    @SuppressLint("RtlHardcoded")   // the same screen corner in Arabic and English: it is a picture, not text
    private boolean open() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels) / 3;   // 640 on a 1920x1080 panel
        int videoH = w * 9 / 16;
        float dp = dm.density;
        int margin = Math.round(24 * dp);
        int pad = Math.round(8 * dp);

        Card card = new Card(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0151618);
        bg.setCornerRadius(14 * dp);
        bg.setStroke(Math.max(1, Math.round(dp)), 0x40FFFFFF);
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 14 * dp);
            }
        });
        card.setClipToOutline(true);

        StreamView video = new StreamView(context, MAX_RETRIES);
        video.setBackgroundColor(Color.BLACK);
        card.addView(video, new LinearLayout.LayoutParams(-1, videoH));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(pad, pad, pad, pad);
        TextView full = button(R.string.popup_full, dp);
        TextView close = button(R.string.popup_close, dp);
        bar.addView(full, weighted(dp, true));   // Close ends the bar, nearest the screen's edge in English
        bar.addView(close, weighted(dp, false));
        card.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        full.setOnClickListener(v -> listener.onFullScreen(channel));
        close.setOnClickListener(v -> listener.onDismiss());

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        p.x = margin;
        p.y = margin;
        p.setTitle("Cam Popup");
        try {
            wm.addView(card, p);
        } catch (RuntimeException e) {
            Log.w(Settings.TAG, "overlay refused (appops SYSTEM_ALERT_WINDOW?): " + e);
            return false;
        }
        root = card;
        stream = video;
        card.first = close;
        close.requestFocus();
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(200).start();
        return true;
    }

    private TextView button(int text, float dp) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(context.getColorStateList(R.color.button_text));
        b.setBackgroundResource(R.drawable.button);
        b.setFocusable(true);
        b.setClickable(true);
        b.setFocusableInTouchMode(true);
        b.setSingleLine(true);
        b.setMinHeight(Math.round(40 * dp));
        return b;
    }

    private static LinearLayout.LayoutParams weighted(float dp, boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (first) lp.setMarginEnd(Math.round(8 * dp));
        return lp;
    }
}
