package io.github.abd3lraouf.campopup;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

/**
 * The picture-in-picture window: a quarter-width 16:9 box in the bottom corner, drawn over everything as an
 * application overlay that never takes focus or touches, so the remote keeps driving whatever is on screen.
 * Plays one channel's RTSP stream through Media3, muted, with small buffers. Main thread only.
 */
@OptIn(markerClass = UnstableApi.class)
final class Popup {
    private static final long RETRY_MS = 3_000;
    private static final int MAX_RETRIES = 3;

    private final Context context;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private TextureView video;
    private TextView label;
    private TextView note;
    private ExoPlayer player;
    private String url;
    private int retries;

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
        label.setText(context.getString(R.string.popup_label, channel));
        if (!rtspUrl.equals(url)) {
            url = rtspUrl;
            retries = 0;
            play();
        }
        return true;
    }

    void close() {
        main.removeCallbacksAndMessages(null);
        releasePlayer();
        url = null;
        if (root != null) {
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

        root = new FrameLayout(context);
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

        video = new TextureView(context);
        root.addView(video, new FrameLayout.LayoutParams(w, h));

        note = new TextView(context);
        note.setTextColor(0xCCFFFFFF);
        note.setTextSize(14);
        note.setText(R.string.connecting);
        root.addView(note, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        LinearLayout tag = new LinearLayout(context);
        tag.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(6 * dp);
        tag.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(0x99000000);
        tagBg.setCornerRadius(8 * dp);
        tag.setBackground(tagBg);
        View dot = new View(context);
        GradientDrawable red = new GradientDrawable();
        red.setShape(GradientDrawable.OVAL);
        red.setColor(0xFFE53935);
        dot.setBackground(red);
        int ds = Math.round(8 * dp);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ds, ds);
        dl.setMarginEnd(pad);
        tag.addView(dot, dl);
        label = new TextView(context);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        tag.addView(label);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.setMargins(pad, pad, pad, pad);
        root.addView(tag, tl);

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

    private void play() {
        releasePlayer();
        if (note != null) {
            note.setText(R.string.connecting);
            note.setVisibility(View.VISIBLE);
        }
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 500, 500)
                .build();
        player = new ExoPlayer.Builder(context).setLoadControl(load).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        player.setVolume(0f);
        player.setVideoTextureView(video);
        final long started = android.os.SystemClock.elapsedRealtime();
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                Log.i(Settings.TAG, "video playing after " + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
                if (note != null) note.setVisibility(View.GONE);
            }

            @Override public void onPlayerError(PlaybackException error) {
                Log.w(Settings.TAG, "video: " + error.getErrorCodeName() + " " + Settings.redact(String.valueOf(url)));
                releasePlayer();
                if (note != null) {
                    note.setText(R.string.no_video);
                    note.setVisibility(View.VISIBLE);
                }
                if (url != null && retries++ < MAX_RETRIES) main.postDelayed(Popup.this::play, RETRY_MS);
            }
        });
        // TCP-interleaved RTP: no UDP ports to open on the TV, and nothing lost on a busy Wi-Fi.
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8_000)
                .setSocketFactory(SdpFix.socketFactory())
                .createMediaSource(MediaItem.fromUri(url)));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
