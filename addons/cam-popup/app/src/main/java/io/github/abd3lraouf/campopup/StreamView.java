package io.github.abd3lraouf.campopup;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
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
 * One camera picture: a video surface, a "Connecting…" / "No video" note and a red-dot label in the top
 * corner. Plays an RTSP stream through Media3, muted, with small buffers, over TCP, and retries a dropped
 * stream every few seconds. Used by the corner pop-up and by every tile of the live view. Main thread only.
 */
@OptIn(markerClass = UnstableApi.class)
final class StreamView extends FrameLayout {
    private static final long RETRY_MS = 3_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextureView video;
    private final TextView note;
    private final TextView label;
    private final int maxRetries;
    private ExoPlayer player;
    private String url;
    private int retries;

    /** @param maxRetries how many times a failed stream is retried; the live view retries without end. */
    StreamView(Context context, int maxRetries) {
        super(context);
        this.maxRetries = maxRetries;
        float dp = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);

        video = new TextureView(context);
        addView(video, new LayoutParams(-1, -1));

        note = new TextView(context);
        note.setTextColor(0xCCFFFFFF);
        note.setTextSize(14);
        note.setText(R.string.connecting);
        addView(note, new LayoutParams(-2, -2, Gravity.CENTER));

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
        LayoutParams tl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.setMargins(pad, pad, pad, pad);
        addView(tag, tl);
    }

    void setLabel(CharSequence text) {
        label.setText(text);
    }

    /** Plays this stream, or keeps playing if it already is. */
    void play(String rtspUrl) {
        if (rtspUrl.equals(url) && player != null) return;
        url = rtspUrl;
        retries = 0;
        start();
    }

    void stop() {
        main.removeCallbacksAndMessages(null);
        release();
        url = null;
    }

    private void start() {
        release();
        if (url == null) return;   // stopped meanwhile: a late error or retry must not restart anything
        note.setText(R.string.connecting);
        note.setVisibility(VISIBLE);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 500, 500)
                .build();
        player = new ExoPlayer.Builder(getContext()).setLoadControl(load).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build());
        player.setVolume(0f);
        player.setVideoTextureView(video);
        final long started = SystemClock.elapsedRealtime();
        final String playing = url;
        final ExoPlayer self = player;
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                if (player != self) return;
                Log.i(Settings.TAG, "video playing after " + (SystemClock.elapsedRealtime() - started) + " ms");
                note.setVisibility(GONE);
                retries = 0;
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (player != self) return;   // an event from a player already let go
                Log.w(Settings.TAG, "video: " + error.getErrorCodeName() + " " + Settings.redact(playing));
                release();
                note.setText(R.string.no_video);
                note.setVisibility(VISIBLE);
                if (url != null && (maxRetries < 0 || retries++ < maxRetries)) main.postDelayed(StreamView.this::start, RETRY_MS);
            }
        });
        // TCP-interleaved RTP: no UDP ports to open on the TV, and nothing lost on a busy Wi-Fi. SdpFix makes the
        // recorder's H.265 description readable to Media3.
        player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8_000)
                .setSocketFactory(SdpFix.socketFactory())
                .createMediaSource(MediaItem.fromUri(url)));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
