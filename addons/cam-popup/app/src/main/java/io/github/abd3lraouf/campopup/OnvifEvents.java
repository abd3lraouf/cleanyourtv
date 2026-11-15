package io.github.abd3lraouf.campopup;

import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Listens for the recorder's motion events over ONVIF on a background thread: one pull-point subscription,
 * then a PullMessages long poll that the recorder holds open for up to 20 s while nothing happens, so an idle
 * recorder costs one small HTTP request every 20 s. Any failure drops the subscription and starts over with a
 * growing pause; a refused login waits 10 minutes, so a wrong password cannot lock the recorder's account.
 */
final class OnvifEvents {
    interface Sink {
        void motion(int channel, boolean active);

        void status(String text);
    }

    private static final int TERMINATION_S = 120;
    private static final int PULL_TIMEOUT_S = 20;
    private static final long RENEW_EVERY_MS = 60_000;
    private static final long AUTH_BACKOFF_MS = 10 * 60_000;

    private final String host;
    private final String user;
    private final String password;
    private final Sink sink;
    private final SecureRandom random = new SecureRandom();
    private volatile boolean stopped;
    private Thread thread;
    private long clockOffset;   // recorder UTC minus our wall clock

    OnvifEvents(String host, String user, String password, Sink sink) {
        this.host = host;
        this.user = user;
        this.password = password;
        this.sink = sink;
    }

    void start() {
        thread = new Thread(this::loop, "onvif-events");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped = true;
        if (thread != null) thread.interrupt();
    }

    private static final class AuthException extends IOException {
        AuthException(String m) {
            super(m);
        }
    }

    private void loop() {
        long backoff = 5_000;
        while (!stopped) {
            long began = SystemClock.elapsedRealtime();
            try {
                session();
            } catch (AuthException e) {
                sink.status("login refused (" + e.getMessage() + ")");
                Log.w(Settings.TAG, "ONVIF login refused: " + e.getMessage());
                sleep(AUTH_BACKOFF_MS);
                continue;
            } catch (IOException | RuntimeException e) {
                if (stopped) break;
                sink.status("disconnected: " + e.getMessage());
                Log.w(Settings.TAG, "ONVIF events: " + e);
            }
            if (SystemClock.elapsedRealtime() - began > 120_000) backoff = 5_000;
            sleep(backoff);
            backoff = Math.min(backoff * 2, 120_000);
        }
    }

    private void session() throws IOException {
        String base = "http://" + host;
        String time = post(base + "/onvif/device_service", Onvif.envelope("", Onvif.GET_TIME), 8_000);
        long device = Onvif.deviceUtc(time);
        clockOffset = device > 0 ? device - System.currentTimeMillis() : 0;

        Map<String, Integer> map;
        try {
            map = Onvif.channelMap(call(base + "/onvif/media", null, Onvif.GET_PROFILES, 10_000));
        } catch (AuthException e) {
            throw e;
        } catch (IOException e) {
            map = Collections.emptyMap();
            Log.w(Settings.TAG, "GetProfiles failed, channels from token numbers: " + e.getMessage());
        }

        String eventUrl = base + "/onvif/event";
        String created = call(eventUrl, Onvif.addressing(Onvif.ACTION_CREATE, eventUrl),
                Onvif.createPullPoint(TERMINATION_S), 10_000);
        String address = Onvif.subscriptionAddress(created);
        if (address == null) throw new IOException("no pull point address");
        Log.i(Settings.TAG, "pull point open, " + new java.util.HashSet<>(map.values()).size() + " channels mapped");
        sink.status("listening");
        long renewed = SystemClock.elapsedRealtime();
        try {
            while (!stopped) {
                String r = call(address, Onvif.addressing(Onvif.ACTION_PULL, address),
                        Onvif.pullMessages(PULL_TIMEOUT_S, 32), (PULL_TIMEOUT_S + 15) * 1000);
                List<Onvif.Event> events = Onvif.motionEvents(r);
                for (Onvif.Event e : events) {
                    if (e.initial) continue;   // the state at subscribe time, not something that just happened
                    int ch = Onvif.channel(e.source, map);
                    Log.i(Settings.TAG, "motion " + (e.active ? "on" : "off") + " source=" + e.source + " ch=" + ch);
                    if (PopupPolicy.valid(ch)) sink.motion(ch, e.active);
                }
                if (SystemClock.elapsedRealtime() - renewed > RENEW_EVERY_MS) {
                    call(address, Onvif.addressing(Onvif.ACTION_RENEW, address), Onvif.renew(TERMINATION_S), 10_000);
                    renewed = SystemClock.elapsedRealtime();
                }
            }
        } finally {
            try {
                call(address, Onvif.addressing(Onvif.ACTION_UNSUBSCRIBE, address), Onvif.UNSUBSCRIBE, 4_000);
            } catch (IOException | RuntimeException ignored) {
                // it expires by itself
            }
        }
    }

    /** One logged-in SOAP call; a fault becomes an exception that never carries the login. */
    private String call(String url, String addressing, String body, int readTimeoutMs) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String header = Onvif.security(user, password, System.currentTimeMillis() + clockOffset, nonce)
                + (addressing == null ? "" : addressing);
        String r = post(url, Onvif.envelope(header, body), readTimeoutMs);
        String fault = Onvif.fault(r);
        if (fault == null) return r;
        if (Onvif.notAuthorized(fault)) throw new AuthException(fault);
        throw new IOException(fault);
    }

    private static String post(String url, String envelope, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6_000);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
            byte[] data = envelope.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(data);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in);
            if (code >= 400 && Onvif.fault(text) == null) throw new IOException("HTTP " + code);
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 4 << 20) throw new IOException("response too large");
            }
            return b.toString("UTF-8");
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }
}
