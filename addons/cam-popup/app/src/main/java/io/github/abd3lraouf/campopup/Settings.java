package io.github.abd3lraouf.campopup;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The add-on's settings, in private SharedPreferences. The recorder's address, user name and password arrive
 * through {@link ConfigProvider} (provision.sh pipes them in over ADB, never on a command line) and never leave
 * the app: no screen shows the password, and nothing logs it. The static helpers are pure and unit tested.
 */
final class Settings {
    static final String TAG = "CamPopup";
    static final String PREFS = "campopup";

    static final String HOST = "host";
    static final String USER = "user";
    static final String PASSWORD = "password";
    static final String ENABLED = "enabled";
    static final String CHANNELS = "channels";
    static final String COOLDOWN = "cooldown_s";
    static final String HOLD = "hold_s";
    static final String STREAM = "stream";
    static final String VIEW = "view";

    static final int[] COOLDOWN_STEPS = {0, 60, 120, 300, 600, 1800};
    static final int DEFAULT_COOLDOWN = 120;
    static final int DEFAULT_HOLD = 30;
    static final int CHANNEL_COUNT = 8;
    static final String ALL_CHANNELS = "1-" + CHANNEL_COUNT;

    final String host;
    final String user;
    final String password;
    final boolean enabled;
    final TreeSet<Integer> channels;
    final int cooldownSeconds;
    final int holdSeconds;
    final String stream;
    /** The cameras the live view shows, in order. */
    final TreeSet<Integer> view;

    private Settings(SharedPreferences p) {
        host = p.getString(HOST, "");
        user = p.getString(USER, "");
        password = p.getString(PASSWORD, "");
        enabled = p.getBoolean(ENABLED, true);
        channels = parseChannels(p.getString(CHANNELS, ALL_CHANNELS));
        cooldownSeconds = p.getInt(COOLDOWN, DEFAULT_COOLDOWN);
        holdSeconds = p.getInt(HOLD, DEFAULT_HOLD);
        stream = p.getString(STREAM, "s1");
        view = parseChannels(p.getString(VIEW, "1-4"));
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Settings load(Context c) {
        return new Settings(prefs(c));
    }

    boolean configured() {
        return !host.isEmpty() && !user.isEmpty();
    }

    boolean allows(int channel) {
        return enabled && channels.contains(channel);
    }

    String rtspUrl(int channel) {
        return rtspUrl(host, user, password, channel, stream);
    }

    /** {@code main} picks the full-resolution stream, for a camera shown full screen. */
    String rtspUrl(int channel, boolean main) {
        return rtspUrl(host, user, password, channel, main ? "s0" : "s1");
    }

    // --- pure helpers -----------------------------------------------------------------------------------------

    /**
     * The XVR's live stream: {@code rtsp://host:554/unicast/c<N>/s0/live} is the main stream and
     * {@code s1} the low-resolution sub-stream. The login rides in the URL's user info, which Media3 takes out
     * and answers the recorder's Digest/Basic challenge with.
     */
    static String rtspUrl(String host, String user, String password, int channel, String stream) {
        String auth = user.isEmpty() ? "" : enc(user) + ":" + enc(password) + "@";
        String h = host.contains(":") ? host : host + ":554";
        return "rtsp://" + auth + h + "/unicast/c" + channel + "/" + stream + "/live";
    }

    /** The same URL with the login cut out, for logs. */
    static String redact(String url) {
        return url.replaceFirst("//[^/@]*@", "//");
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** "1,3,5-8" to {1,3,5,6,7,8}; anything out of range or unreadable is skipped. */
    static TreeSet<Integer> parseChannels(String s) {
        TreeSet<Integer> out = new TreeSet<>();
        if (s == null) return out;
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                int dash = part.indexOf('-');
                int from = Integer.parseInt((dash < 0 ? part : part.substring(0, dash)).trim());
                int to = dash < 0 ? from : Integer.parseInt(part.substring(dash + 1).trim());
                for (int c = Math.max(1, from); c <= Math.min(PopupPolicy.MAX_CHANNEL, to); c++) out.add(c);
            } catch (NumberFormatException ignored) {
                // skip it
            }
        }
        return out;
    }

    static String formatChannels(TreeSet<Integer> set) {
        StringBuilder b = new StringBuilder();
        Integer start = null, prev = null;
        for (int c : set) {
            if (start == null) {
                start = prev = c;
            } else if (c == prev + 1) {
                prev = c;
            } else {
                append(b, start, prev);
                start = prev = c;
            }
        }
        if (start != null) append(b, start, prev);
        return b.toString();
    }

    private static void append(StringBuilder b, int from, int to) {
        if (b.length() > 0) b.append(',');
        b.append(from);
        if (to != from) b.append('-').append(to);
    }

    /** The next cooldown step after {@code seconds}, wrapping round. */
    static int nextCooldown(int seconds) {
        for (int step : COOLDOWN_STEPS) if (step > seconds) return step;
        return COOLDOWN_STEPS[0];
    }

    /**
     * The provisioning text provision.sh sends: one {@code key=value} per line, split at the first '=' so a
     * password may hold one. Only known keys are kept; values keep their spaces except a trailing CR.
     */
    static Map<String, String> parseProvision(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(eq + 1);
            switch (key) {
                case HOST: case USER: case STREAM: case CHANNELS: case VIEW:
                    out.put(key, value.trim());
                    break;
                case PASSWORD:
                    out.put(key, value);
                    break;
                case ENABLED: case COOLDOWN: case HOLD:
                    out.put(key, value.trim());
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    /** Writes provisioned keys; returns how many were taken. */
    static int apply(Context c, Map<String, String> values) {
        SharedPreferences.Editor e = prefs(c).edit();
        int n = 0;
        for (Map.Entry<String, String> v : values.entrySet()) {
            String k = v.getKey();
            String s = v.getValue();
            try {
                switch (k) {
                    case ENABLED: e.putBoolean(k, s.equals("1") || s.equalsIgnoreCase("true")); break;
                    case COOLDOWN: case HOLD: e.putInt(k, Math.max(0, Integer.parseInt(s))); break;
                    case CHANNELS: case VIEW: e.putString(k, formatChannels(parseChannels(s))); break;
                    case STREAM: if (!s.equals("s0") && !s.equals("s1")) continue; e.putString(k, s); break;
                    default: e.putString(k, s); break;
                }
                n++;
            } catch (NumberFormatException ignored) {
                // skip it
            }
        }
        e.apply();
        return n;
    }
}
