package io.github.abd3lraouf.campopup;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;

/**
 * Some recorders describe H.265 the H.264 way: {@code sprop-parameter-sets=<SPS>,<PPS>} with no VPS, where
 * RFC 7798 wants {@code sprop-vps}, {@code sprop-sps} and {@code sprop-pps}. Media3 refuses such a stream
 * ("missing sprop-vps parameter"). This rewrites the SDP in the RTSP DESCRIBE reply as it arrives: it splits
 * the old list by NAL type and, when no VPS was sent, builds one from the SPS's profile, tier and level. The
 * recorder repeats its real VPS in the stream before every key frame, so the decoder ends up using that one.
 */
final class SdpFix {
    private SdpFix() {}

    private static final Pattern RTPMAP_H265 = Pattern.compile("a=rtpmap:(\\d+) H265/", Pattern.CASE_INSENSITIVE);

    /** A {@link SocketFactory} whose sockets fix the SDP on the way in. Pass to RtspMediaSource.Factory. */
    static SocketFactory socketFactory() {
        return new SocketFactory() {
            @Override public Socket createSocket() {
                return new FixingSocket();
            }

            @Override public Socket createSocket(String host, int port) throws IOException {
                FixingSocket s = new FixingSocket();
                s.connect(new java.net.InetSocketAddress(host, port));
                return s;
            }

            @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
                FixingSocket s = new FixingSocket();
                s.bind(new java.net.InetSocketAddress(local, localPort));
                s.connect(new java.net.InetSocketAddress(host, port));
                return s;
            }

            @Override public Socket createSocket(InetAddress host, int port) throws IOException {
                FixingSocket s = new FixingSocket();
                s.connect(new java.net.InetSocketAddress(host, port));
                return s;
            }

            @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
                FixingSocket s = new FixingSocket();
                s.bind(new java.net.InetSocketAddress(local, localPort));
                s.connect(new java.net.InetSocketAddress(host, port));
                return s;
            }
        };
    }

    private static final class FixingSocket extends Socket {
        private InputStream in;

        @Override public synchronized InputStream getInputStream() throws IOException {
            if (in == null) in = new FixingInputStream(super.getInputStream());
            return in;
        }
    }

    /**
     * Passes the RTSP connection through unchanged except for SDP bodies. Messages and interleaved RTP
     * ({@code $}, channel, 16-bit length, payload) are walked one at a time, so binary data is never parsed
     * as text.
     */
    static final class FixingInputStream extends FilterInputStream {
        private byte[] pending = new byte[0];
        private int pos;
        private int rawLeft; // bytes of an interleaved RTP payload still to pass through untouched

        FixingInputStream(InputStream in) {
            super(in);
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (pos < pending.length) {
                int n = Math.min(len, pending.length - pos);
                System.arraycopy(pending, pos, b, off, n);
                pos += n;
                return n;
            }
            if (rawLeft > 0) {
                int n = in.read(b, off, Math.min(len, rawLeft));
                if (n > 0) rawLeft -= n;
                return n;
            }
            int first = in.read();
            if (first < 0) return -1;
            if (first == '$') {
                byte[] head = new byte[4];
                head[0] = '$';
                readFully(head, 1, 3);
                rawLeft = ((head[2] & 0xff) << 8) | (head[3] & 0xff);
                pending = head;
            } else {
                pending = readMessage(first);
            }
            pos = 0;
            return read(b, off, len);
        }

        @Override public int available() throws IOException {
            return (pending.length - pos) + (rawLeft > 0 ? Math.min(rawLeft, in.available()) : 0);
        }

        @Override public boolean markSupported() {
            return false;
        }

        private byte[] readMessage(int first) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream(512);
            head.write(first);
            int c;
            while ((c = in.read()) >= 0) {
                head.write(c);
                byte[] h = head.toByteArray();
                int n = h.length;
                if (n >= 4 && h[n - 4] == '\r' && h[n - 3] == '\n' && h[n - 2] == '\r' && h[n - 1] == '\n') break;
                if (n > 64 * 1024) break; // not RTSP; hand it on as is
            }
            String header = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
            int length = headerInt(header, "content-length");
            if (length <= 0) return head.toByteArray();
            byte[] body = new byte[length];
            readFully(body, 0, length);
            if (!header.toLowerCase(Locale.ROOT).contains("application/sdp")) return concat(head.toByteArray(), body);
            String sdp = new String(body, StandardCharsets.UTF_8);
            String fixed = fix(sdp);
            if (fixed.equals(sdp)) return concat(head.toByteArray(), body);
            byte[] newBody = fixed.getBytes(StandardCharsets.UTF_8);
            String newHeader = header.replaceFirst("(?i)(content-length:\\s*)\\d+", "$1" + newBody.length);
            return concat(newHeader.getBytes(StandardCharsets.ISO_8859_1), newBody);
        }

        private void readFully(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = in.read(b, off, len);
                if (n < 0) throw new java.io.EOFException();
                off += n;
                len -= n;
            }
        }
    }

    static int headerInt(String header, String name) {
        for (String line : header.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                try {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Rewrites every H.265 fmtp line that lacks sprop-vps but has sprop-parameter-sets. Anything else is kept. */
    static String fix(String sdp) {
        Matcher m = RTPMAP_H265.matcher(sdp);
        String out = sdp;
        while (m.find()) {
            String pt = m.group(1);
            Matcher f = Pattern.compile("(?m)^a=fmtp:" + pt + " ([^\r\n]*)").matcher(out);
            if (!f.find()) continue;
            String params = f.group(1);
            if (params.contains("sprop-vps=")) continue;
            Matcher sets = Pattern.compile("sprop-parameter-sets=([^;\\s]+)").matcher(params);
            if (!sets.find()) continue;
            String vps = null, sps = null, pps = null;
            for (String b64 : sets.group(1).split(",")) {
                byte[] nal;
                try {
                    nal = java.util.Base64.getDecoder().decode(b64);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (nal.length < 2) continue;
                int type = (nal[0] >> 1) & 0x3f;
                if (type == 32) vps = b64;
                else if (type == 33) sps = b64;
                else if (type == 34) pps = b64;
            }
            if (sps == null || pps == null) continue;
            if (vps == null) {
                byte[] built = vpsFromSps(java.util.Base64.getDecoder().decode(sps));
                if (built == null) continue;
                vps = java.util.Base64.getEncoder().encodeToString(built);
            }
            String newParams = params.replace(sets.group(0),
                    "sprop-vps=" + vps + ";sprop-sps=" + sps + ";sprop-pps=" + pps);
            out = out.substring(0, f.start(1)) + newParams + out.substring(f.end(1));
        }
        return out;
    }

    /**
     * A minimal VPS (NAL type 32) carrying the SPS's general profile, tier and level: one layer, one layer
     * set, no timing info, no extension. Null when the SPS is too short or has sub-layers.
     */
    static byte[] vpsFromSps(byte[] spsNal) {
        byte[] rbsp = unescape(spsNal);
        // 2-byte NAL header, then sps_video_parameter_set_id(4) sps_max_sub_layers_minus1(3) nesting(1),
        // then a 12-byte general profile_tier_level when there are no sub-layers.
        if (rbsp.length < 2 + 1 + 12) return null;
        int maxSubLayersMinus1 = (rbsp[2] >> 1) & 0x7;
        if (maxSubLayersMinus1 != 0) return null;
        byte[] v = new byte[2 + 4 + 12 + 2];
        v[0] = 0x40; // type 32, layer 0
        v[1] = 0x01; // temporal id 1
        v[2] = 0x0C; // vps id 0, base layer internal + available, max layers - 1 = 0 (high bits)
        v[3] = 0x01; // max sub layers - 1 = 0, temporal id nesting = 1
        v[4] = (byte) 0xFF; // reserved 0xffff
        v[5] = (byte) 0xFF;
        System.arraycopy(rbsp, 3, v, 6, 12);
        // sub_layer_ordering_info_present=1, max_dec_pic_buffering_minus1=1 (010), max_num_reorder=0 (1),
        // max_latency_increase_plus1=0 (1), max_layer_id=0 (000000), num_layer_sets_minus1=0 (1),
        // timing_info_present=0, extension=0, then the stop bit: 1010 1100 | 0000 1001
        v[18] = (byte) 0xAC;
        v[19] = 0x09;
        return escape(v);
    }

    static byte[] unescape(byte[] nal) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (byte b : nal) {
            if (zeros >= 2 && b == 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }

    static byte[] escape(byte[] rbsp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rbsp.length + 8);
        int zeros = 0;
        for (byte b : rbsp) {
            if (zeros >= 2 && (b & 0xff) <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(b);
            zeros = b == 0 ? zeros + 1 : 0;
        }
        return out.toByteArray();
    }
}
