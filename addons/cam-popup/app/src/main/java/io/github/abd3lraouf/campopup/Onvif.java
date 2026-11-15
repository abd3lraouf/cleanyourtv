package io.github.abd3lraouf.campopup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The few ONVIF messages the add-on needs, built and read as plain strings: GetSystemDateAndTime (no login),
 * GetProfiles (which token is which channel), CreatePullPointSubscription, PullMessages, Renew and Unsubscribe.
 * Login is a WS-Security UsernameToken with a password digest, stamped with the recorder's own clock (the
 * XVR's clock drifts minutes from the TV's, and a stale Created time is refused). Pure Java: unit tested.
 */
final class Onvif {
    private Onvif() {}

    static final String EVENTS_NS = "http://www.onvif.org/ver10/events/wsdl";
    private static final String WSA = "http://www.w3.org/2005/08/addressing";

    /** One motion notification: the channel's source token as the recorder named it, and on or off. */
    static final class Event {
        final String source;
        final boolean active;
        final boolean initial;

        Event(String source, boolean active, boolean initial) {
            this.source = source;
            this.active = active;
            this.initial = initial;
        }
    }

    // --- building ---------------------------------------------------------------------------------------------

    /** Base64(SHA-1(nonce + created + password)), as WS-Security UsernameToken Profile 1.0 defines it. */
    static String passwordDigest(byte[] nonce, String created, String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(nonce);
            sha.update(created.getBytes(StandardCharsets.UTF_8));
            sha.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String created(long utcMillis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(utcMillis));
    }

    static String security(String user, String password, long deviceUtcMillis, byte[] nonce) {
        String created = created(deviceUtcMillis);
        return "<wsse:Security s:mustUnderstand=\"1\""
                + " xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\""
                + " xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">"
                + "<wsse:UsernameToken><wsse:Username>" + xml(user) + "</wsse:Username>"
                + "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">"
                + passwordDigest(nonce, created, password) + "</wsse:Password>"
                + "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">"
                + Base64.getEncoder().encodeToString(nonce) + "</wsse:Nonce>"
                + "<wsu:Created>" + created + "</wsu:Created></wsse:UsernameToken></wsse:Security>";
    }

    static String envelope(String header, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"" + WSA + "\">"
                + "<s:Header>" + header + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>";
    }

    /** WS-Addressing headers: a pull point lives at its own address and some recorders route by wsa:To. */
    static String addressing(String action, String to) {
        return "<a:Action s:mustUnderstand=\"1\">" + xml(action) + "</a:Action>"
                + "<a:To s:mustUnderstand=\"1\">" + xml(to) + "</a:To>";
    }

    static final String GET_TIME = "<GetSystemDateAndTime xmlns=\"http://www.onvif.org/ver10/device/wsdl\"/>";
    static final String GET_PROFILES = "<GetProfiles xmlns=\"http://www.onvif.org/ver10/media/wsdl\"/>";

    static String createPullPoint(int terminationSeconds) {
        return "<CreatePullPointSubscription xmlns=\"" + EVENTS_NS + "\">"
                + "<InitialTerminationTime>PT" + terminationSeconds + "S</InitialTerminationTime>"
                + "</CreatePullPointSubscription>";
    }

    static String pullMessages(int timeoutSeconds, int limit) {
        return "<PullMessages xmlns=\"" + EVENTS_NS + "\"><Timeout>PT" + timeoutSeconds + "S</Timeout>"
                + "<MessageLimit>" + limit + "</MessageLimit></PullMessages>";
    }

    static String renew(int terminationSeconds) {
        return "<Renew xmlns=\"http://docs.oasis-open.org/wsn/b-2\"><TerminationTime>PT" + terminationSeconds
                + "S</TerminationTime></Renew>";
    }

    static final String UNSUBSCRIBE = "<Unsubscribe xmlns=\"http://docs.oasis-open.org/wsn/b-2\"/>";

    static final String ACTION_CREATE = EVENTS_NS + "/EventPortType/CreatePullPointSubscriptionRequest";
    static final String ACTION_PULL = EVENTS_NS + "/PullPointSubscription/PullMessagesRequest";
    static final String ACTION_RENEW = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/RenewRequest";
    static final String ACTION_UNSUBSCRIBE = "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest";

    // --- reading ----------------------------------------------------------------------------------------------

    private static final Pattern FAULT = Pattern.compile("<(?:\\w+:)?Fault[\\s>]");
    private static final Pattern FAULT_SUBCODE = Pattern.compile(
            "<(?:\\w+:)?Subcode>\\s*<(?:\\w+:)?Value>([^<]*)</", Pattern.DOTALL);
    private static final Pattern FAULT_TEXT = Pattern.compile("<(?:\\w+:)?Text[^>]*>([^<]*)</", Pattern.DOTALL);

    /** The fault's subcode or reason ("ter:NotAuthorized"), or null when the response is not a fault. */
    static String fault(String xml) {
        if (xml == null || !FAULT.matcher(xml).find()) return null;
        Matcher m = FAULT_SUBCODE.matcher(xml);
        if (m.find()) return m.group(1).trim();
        m = FAULT_TEXT.matcher(xml);
        return m.find() ? m.group(1).trim() : "fault";
    }

    static boolean notAuthorized(String fault) {
        return fault != null && fault.toLowerCase(Locale.ROOT).contains("notauthorized");
    }

    private static int tag(String xml, String name, int from) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + ">\\s*(-?\\d+)\\s*<").matcher(xml);
        return m.find(from) ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The recorder's UTC clock from a GetSystemDateAndTime response, in millis, or -1. */
    static long deviceUtc(String xml) {
        int at = xml.indexOf("UTCDateTime>");
        if (at < 0) return -1;
        int y = tag(xml, "Year", at), mo = tag(xml, "Month", at), d = tag(xml, "Day", at);
        int h = tag(xml, "Hour", at), mi = tag(xml, "Minute", at), s = tag(xml, "Second", at);
        if (y < 0 || mo < 0 || d < 0 || h < 0 || mi < 0 || s < 0) return -1;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTimeInMillis();
    }

    private static final Pattern ADDRESS = Pattern.compile(
            "<(?:\\w+:)?SubscriptionReference>.*?<(?:\\w+:)?Address>\\s*([^<\\s]+)\\s*</", Pattern.DOTALL);

    static String subscriptionAddress(String xml) {
        Matcher m = ADDRESS.matcher(xml);
        return m.find() ? unxml(m.group(1)) : null;
    }

    private static final Pattern PROFILE = Pattern.compile(
            "<(?:\\w+:)?Profiles\\b[^>]*\\btoken=\"([^\"]*)\"(.*?)</(?:\\w+:)?Profiles>", Pattern.DOTALL);
    private static final Pattern VSC = Pattern.compile(
            "<(?:\\w+:)?VideoSourceConfiguration\\b[^>]*\\btoken=\"([^\"]*)\"(.*?)</(?:\\w+:)?VideoSourceConfiguration>",
            Pattern.DOTALL);
    private static final Pattern SOURCE_TOKEN = Pattern.compile("<(?:\\w+:)?SourceToken>\\s*([^<\\s]+)\\s*</");

    /**
     * Channel numbers from GetProfiles: video sources numbered 1, 2, 3... in the order the recorder lists them,
     * and each profile and video source configuration token mapped to its source's channel, so an event that
     * names any of the three finds its channel.
     */
    static Map<String, Integer> channelMap(String xml) {
        Map<String, Integer> sources = new LinkedHashMap<>();
        Map<String, Integer> all = new LinkedHashMap<>();
        Matcher p = PROFILE.matcher(xml);
        while (p.find()) {
            Matcher v = VSC.matcher(p.group(2));
            if (!v.find()) continue;
            Matcher s = SOURCE_TOKEN.matcher(v.group(2));
            if (!s.find()) continue;
            String source = s.group(1);
            Integer ch = sources.get(source);
            if (ch == null) {
                ch = sources.size() + 1;
                sources.put(source, ch);
            }
            all.put(source, ch);
            if (!all.containsKey(v.group(1))) all.put(v.group(1), ch);
            all.put(p.group(1), ch);
        }
        return all;
    }

    private static final Pattern DIGITS = Pattern.compile("(\\d+)\\D*$");

    /** The channel an event's source token stands for: from the profile map, else the token's last number. */
    static int channel(String token, Map<String, Integer> map) {
        if (token == null) return -1;
        Integer ch = map.get(token);
        if (ch != null) return ch;
        Matcher m = DIGITS.matcher(token);
        if (!m.find()) return -1;
        try {
            int n = Integer.parseInt(m.group(1));
            return n == 0 ? 1 : n;   // "VideoSource_0" style tokens count from zero
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final Pattern MESSAGE = Pattern.compile(
            "<(?:\\w+:)?NotificationMessage>(.*?)</(?:\\w+:)?NotificationMessage>", Pattern.DOTALL);
    private static final Pattern TOPIC = Pattern.compile("<(?:\\w+:)?Topic\\b[^>]*>\\s*([^<]*?)\\s*</", Pattern.DOTALL);
    private static final Pattern OPERATION = Pattern.compile("PropertyOperation=\"(\\w+)\"");
    private static final Pattern SECTION_SOURCE = Pattern.compile(
            "<(?:\\w+:)?Source>(.*?)</(?:\\w+:)?Source>", Pattern.DOTALL);
    private static final Pattern SECTION_DATA = Pattern.compile(
            "<(?:\\w+:)?Data>(.*?)</(?:\\w+:)?Data>", Pattern.DOTALL);
    private static final Pattern ITEM = Pattern.compile(
            "<(?:\\w+:)?SimpleItem\\b(?=[^>]*\\bName=\"([^\"]*)\")(?=[^>]*\\bValue=\"([^\"]*)\")[^>]*/?>");

    /**
     * The motion notifications in a PullMessages response. A motion topic is any topic with "Motion" in it
     * (RuleEngine/CellMotionDetector/Motion with IsMotion, VideoSource/MotionAlarm with State, and the
     * MotionRegionDetector variants); its on/off is the first Data item that reads true or false.
     */
    static List<Event> motionEvents(String xml) {
        List<Event> out = new ArrayList<>();
        Matcher m = MESSAGE.matcher(xml);
        while (m.find()) {
            String msg = m.group(1);
            Matcher t = TOPIC.matcher(msg);
            if (!t.find() || !t.group(1).toLowerCase(Locale.ROOT).contains("motion")) continue;
            Matcher d = SECTION_DATA.matcher(msg);
            if (!d.find()) continue;
            Boolean active = null;
            Matcher item = ITEM.matcher(d.group(1));
            while (item.find() && active == null) {
                String v = item.group(2).trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1")) active = true;
                else if (v.equals("false") || v.equals("0")) active = false;
            }
            if (active == null) continue;
            String source = null;
            Matcher s = SECTION_SOURCE.matcher(msg);
            if (s.find()) {
                Matcher si = ITEM.matcher(s.group(1));
                // Prefer the item that names the video source; else the first one.
                String first = null;
                while (si.find()) {
                    if (first == null) first = si.group(2);
                    String name = si.group(1).toLowerCase(Locale.ROOT);
                    if (name.contains("videosource") || name.equals("source") || name.contains("input")) {
                        source = si.group(2);
                        break;
                    }
                }
                if (source == null) source = first;
            }
            Matcher op = OPERATION.matcher(msg);
            boolean initial = op.find() && op.group(1).equals("Initialized");
            out.add(new Event(source, active, initial));
        }
        return out;
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String unxml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
    }
}
