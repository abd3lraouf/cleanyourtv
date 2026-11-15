package io.github.abd3lraouf.campopup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

public class SdpFixTest {
    // Parameter sets as a Uniview recorder sends them: H.264-style list, no VPS.
    private static final String SPS = "QgEBAWAAAAMAAAMAAAMAAAMAWqAFogCQWNrkkya5ZwCAAAH0AAAw1AQ=";
    private static final String PPS = "RAHgdrAmQAA=";
    private static final String SDP = "v=0\r\n"
            + "o=- 0 0 IN IP4 192.0.2.10\r\n"
            + "m=video 0 RTP/AVP 108\r\n"
            + "a=rtpmap:108 H265/90000\r\n"
            + "a=fmtp:108 packetization-mode=0;profile-level-id=010101;sprop-parameter-sets=" + SPS + "," + PPS + "\r\n"
            + "m=audio 0 RTP/AVP 0\r\n"
            + "a=rtpmap:0 PCMU/8000\r\n";

    @Test public void builtVpsCarriesTheSpsProfileTierLevel() {
        byte[] vps = SdpFix.vpsFromSps(Base64.getDecoder().decode(SPS));
        // Same first 22 bytes as the VPS the recorder sends in band; it then adds timing info we leave out.
        byte[] inBand = Base64.getDecoder().decode("QAEMAf//AWAAAAMAAAMAAAMAAAMAWqwMAAAPoAABhqFA");
        byte[] head = new byte[22];
        System.arraycopy(inBand, 0, head, 0, 22);
        byte[] ours = new byte[22];
        System.arraycopy(vps, 0, ours, 0, 22);
        assertArrayEquals(head, ours);
        assertEquals(24, vps.length);
    }

    @Test public void fixesH265FmtpAndLeavesTheRestAlone() {
        String fixed = SdpFix.fix(SDP);
        assertTrue(fixed.contains("sprop-vps="));
        assertTrue(fixed.contains(";sprop-sps=" + SPS + ";sprop-pps=" + PPS));
        assertTrue(!fixed.contains("sprop-parameter-sets"));
        assertTrue(fixed.contains("a=rtpmap:0 PCMU/8000\r\n"));
        assertEquals(fixed, SdpFix.fix(fixed));
    }

    @Test public void h264IsUntouched() {
        String h264 = "a=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0IAH5WoFAFuQA==,aM48gA==\r\n";
        assertEquals(h264, SdpFix.fix(h264));
    }

    @Test public void streamRewritesDescribeBodyAndPassesRtpThrough() throws Exception {
        byte[] body = SDP.getBytes(StandardCharsets.UTF_8);
        String head = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: " + body.length + "\r\n\r\n";
        byte[] rtp = {'$', 0, 0, 5, '$', 'R', '\r', '\n', 9};
        String next = "RTSP/1.0 200 OK\r\nCSeq: 3\r\n\r\n";
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(head.getBytes(StandardCharsets.ISO_8859_1));
        wire.write(body);
        wire.write(rtp);
        wire.write(next.getBytes(StandardCharsets.ISO_8859_1));

        InputStream in = new SdpFix.FixingInputStream(new ByteArrayInputStream(wire.toByteArray()));
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        byte[] buf = new byte[7];
        int n;
        while ((n = in.read(buf, 0, buf.length)) > 0) got.write(buf, 0, n);
        String all = new String(got.toByteArray(), StandardCharsets.ISO_8859_1);

        String fixed = SdpFix.fix(SDP);
        int len = fixed.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(all.startsWith("RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: " + len + "\r\n\r\n" + fixed));
        assertTrue(all.endsWith(new String(rtp, StandardCharsets.ISO_8859_1) + next));
    }
}
