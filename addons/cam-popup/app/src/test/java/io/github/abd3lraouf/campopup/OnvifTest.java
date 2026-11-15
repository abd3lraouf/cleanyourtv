package io.github.abd3lraouf.campopup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class OnvifTest {
    @Test public void passwordDigestMatchesTheOnvifSpecExample() {
        // WS-Security UsernameToken profile: Base64(SHA-1(nonce + created + password)).
        byte[] nonce = java.util.Base64.getDecoder().decode("LKqI6G/AikKCQrN0zqZFlg==");
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=",
                Onvif.passwordDigest(nonce, "2010-09-16T07:50:45Z", "userpassword"));
    }

    @Test public void securityHeaderUsesTheDeviceClockAndEscapes() {
        String h = Onvif.security("a<b", "pw", 0L, "0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        assertTrue(h.contains("<wsse:Username>a&lt;b</wsse:Username>"));
        assertTrue(h.contains("1970-01-01T00:00:00Z"));
        assertFalse(h.contains(">pw<"));
    }

    @Test public void faults() {
        String f = "<env:Envelope><env:Body><env:Fault><env:Code><env:Value>env:Sender</env:Value>"
                + "<env:Subcode><env:Value>ter:NotAuthorized</env:Value></env:Subcode></env:Code>"
                + "<env:Reason><env:Text xml:lang=\"en\">Sender not Authorized</env:Text></env:Reason>"
                + "</env:Fault></env:Body></env:Envelope>";
        String fault = Onvif.fault(f);
        assertTrue(fault, fault.contains("NotAuthorized"));
        assertTrue(Onvif.notAuthorized(fault));
        assertNull(Onvif.fault("<s:Envelope><s:Body><tev:PullMessagesResponse/></s:Body></s:Envelope>"));
    }

    @Test public void deviceUtc() {
        String xml = "<tt:UTCDateTime><tt:Time><tt:Hour>2</tt:Hour><tt:Minute>7</tt:Minute><tt:Second>5</tt:Second>"
                + "</tt:Time><tt:Date><tt:Year>2026</tt:Year><tt:Month>11</tt:Month><tt:Day>15</tt:Day></tt:Date>"
                + "</tt:UTCDateTime>";
        assertEquals(1794708425000L, Onvif.deviceUtc(xml));
        assertEquals(-1, Onvif.deviceUtc("<nothing/>"));
    }

    @Test public void subscriptionAddress() {
        String xml = "<tev:CreatePullPointSubscriptionResponse><tev:SubscriptionReference>"
                + "<wsa5:Address>http://192.0.2.50/onvif/event?sub=1&amp;x=2</wsa5:Address>"
                + "</tev:SubscriptionReference></tev:CreatePullPointSubscriptionResponse>";
        assertEquals("http://192.0.2.50/onvif/event?sub=1&x=2", Onvif.subscriptionAddress(xml));
    }

    private static String profile(String token, String vsc, String source) {
        return "<trt:Profiles fixed=\"true\" token=\"" + token + "\"><tt:Name>x</tt:Name>"
                + "<tt:VideoSourceConfiguration token=\"" + vsc + "\"><tt:Name>v</tt:Name>"
                + "<tt:SourceToken>" + source + "</tt:SourceToken></tt:VideoSourceConfiguration></trt:Profiles>";
    }

    @Test public void channelMapNumbersSourcesInOrder() {
        String xml = profile("p1main", "vsc1", "src_a") + profile("p1sub", "vsc1", "src_a")
                + profile("p2main", "vsc2", "src_b");
        Map<String, Integer> m = Onvif.channelMap(xml);
        assertEquals(1, Onvif.channel("src_a", m));
        assertEquals(1, Onvif.channel("p1sub", m));
        assertEquals(2, Onvif.channel("vsc2", m));
        assertEquals(2, Onvif.channel("src_b", m));
    }

    @Test public void channelFallsBackToTheTokensNumber() {
        Map<String, Integer> none = java.util.Collections.emptyMap();
        assertEquals(3, Onvif.channel("VideoSourceToken3", none));
        assertEquals(1, Onvif.channel("VideoSource_0", none));
        assertEquals(-1, Onvif.channel("abc", none));
        assertEquals(-1, Onvif.channel(null, none));
    }

    private static String message(String topic, String op, String srcName, String src, String dataName, String value) {
        return "<wsnt:NotificationMessage><wsnt:Topic Dialect=\"http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet\">"
                + topic + "</wsnt:Topic><wsnt:Message><tt:Message UtcTime=\"2026-11-15T02:00:00Z\" PropertyOperation=\""
                + op + "\"><tt:Source><tt:SimpleItem Name=\"" + srcName + "\" Value=\"" + src + "\"/>"
                + "<tt:SimpleItem Name=\"Rule\" Value=\"MyMotionDetectorRule\"/></tt:Source>"
                + "<tt:Data><tt:SimpleItem Name=\"" + dataName + "\" Value=\"" + value + "\"/></tt:Data>"
                + "</tt:Message></wsnt:Message></wsnt:NotificationMessage>";
    }

    @Test public void motionEvents() {
        String xml = "<tev:PullMessagesResponse>"
                + message("tns1:RuleEngine/CellMotionDetector/Motion", "Changed", "VideoSourceConfigurationToken", "vsc2", "IsMotion", "true")
                + message("tns1:VideoSource/MotionAlarm", "Initialized", "Source", "src_a", "State", "false")
                + message("tns1:Device/Trigger/DigitalInput", "Changed", "InputToken", "in1", "LogicalState", "true")
                + message("tns1:VideoSource/MotionAlarm", "Changed", "Source", "src_a", "State", "0")
                + "</tev:PullMessagesResponse>";
        List<Onvif.Event> e = Onvif.motionEvents(xml);
        assertEquals(3, e.size());
        assertEquals("vsc2", e.get(0).source);
        assertTrue(e.get(0).active);
        assertFalse(e.get(0).initial);
        assertTrue(e.get(1).initial);
        assertEquals("src_a", e.get(2).source);
        assertFalse(e.get(2).active);
    }

    @Test public void requestsAreWellFormedEnough() {
        String env = Onvif.envelope(Onvif.addressing(Onvif.ACTION_PULL, "http://h/sub?a&b"), Onvif.pullMessages(20, 32));
        assertTrue(env.contains("<tev:Timeout>PT20S</tev:Timeout>") || env.contains("PT20S"));
        assertTrue(env.contains("http://h/sub?a&amp;b"));
        assertTrue(Onvif.createPullPoint(120).contains("PT120S"));
    }
}
