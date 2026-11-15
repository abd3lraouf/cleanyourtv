package io.github.abd3lraouf.campopup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;

import org.junit.Test;

public class SettingsTest {
    @Test public void rtspUrl() {
        assertEquals("rtsp://192.0.2.50:554/unicast/c3/s1/live",
                Settings.rtspUrl("192.0.2.50", "", "", 3, "s1"));
        assertEquals("rtsp://viewer:p%40ss%20w%3Ard@192.0.2.50:8554/unicast/c1/s0/live",
                Settings.rtspUrl("192.0.2.50:8554", "viewer", "p@ss w:rd", 1, "s0"));
    }

    @Test public void redactCutsTheLogin() {
        String url = Settings.rtspUrl("192.0.2.50", "viewer", "secret", 2, "s1");
        String r = Settings.redact(url);
        assertEquals("rtsp://192.0.2.50:554/unicast/c2/s1/live", r);
        assertFalse(r.contains("secret"));
    }

    @Test public void channels() {
        assertEquals(new TreeSet<>(Arrays.asList(1, 3, 5, 6, 7, 8)), Settings.parseChannels("1, 3,5-8"));
        assertEquals(new TreeSet<>(Arrays.asList(1, 2)), Settings.parseChannels("0-2,x,99"));
        assertEquals(new TreeSet<Integer>(), Settings.parseChannels(""));
        assertEquals("1,3,5-8", Settings.formatChannels(Settings.parseChannels("8,7,6,5,3,1")));
        assertEquals("", Settings.formatChannels(new TreeSet<>()));
    }

    @Test public void cooldownSteps() {
        assertEquals(60, Settings.nextCooldown(0));
        assertEquals(300, Settings.nextCooldown(120));
        assertEquals(300, Settings.nextCooldown(200));
        assertEquals(0, Settings.nextCooldown(1800));
    }

    @Test public void provisioningText() {
        Map<String, String> m = Settings.parseProvision(
                "host=192.0.2.50\r\nuser= viewer \npassword= a=b c \nbogus=1\nno equals\nchannels=1-4\nview=2,4\n");
        assertEquals("192.0.2.50", m.get("host"));
        assertEquals("viewer", m.get("user"));
        assertEquals(" a=b c ", m.get("password"));
        assertEquals("1-4", m.get("channels"));
        assertEquals("2,4", m.get("view"));
        assertFalse(m.containsKey("bogus"));
        assertEquals(5, m.size());
    }
}
