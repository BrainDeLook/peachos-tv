package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Plain GET requests for the backdrop sources (artwork, TMDB, YouTube, Plex, HDHomeRun). Worker threads only. */
final class Http {

    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;

    private Http() {
    }

    /** The body of a 200 reply, at most maxBytes long; anything else is an IOException. */
    static byte[] get(String url, Map<String, String> headers, int maxBytes) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelled");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in, maxBytes);
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] get(String url, int maxBytes) throws IOException {
        return get(url, Collections.emptyMap(), maxBytes);
    }

    /** A text (JSON) reply as a string. */
    static String getText(String url, Map<String, String> headers) throws IOException {
        return new String(get(url, headers, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
    }

    /**
     * A text reply from a device on the home network (the HDHomeRun tuner), which only speaks plain HTTP.
     * Android refuses plain HTTP for this app, which keeps it from the internet; this asks over a socket
     * instead, and only addresses on the home network (192.168.x.x and the like).
     */
    static String getLan(String ip, String path) throws IOException {
        // A plain IPv4 address only: a name would be looked up first, and could point anywhere.
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) throw new IOException("not an address: " + ip);
        InetAddress address = InetAddress.getByName(ip);   // a literal address: no lookup
        if (!address.isSiteLocalAddress()) throw new IOException("not on the home network: " + ip);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, 80), 5000);
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + ip + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String reply = new String(readAll(socket.getInputStream(), MAX_TEXT_BYTES), StandardCharsets.UTF_8);
            int body = reply.indexOf("\r\n\r\n");
            if (!reply.startsWith("HTTP/1.") || !reply.startsWith(" 200", 8) || body < 0) {
                throw new IOException("bad reply from " + ip);
            }
            return reply.substring(body + 4);
        }
    }

    /** All of a stream, refusing more than maxBytes (a runaway download). */
    static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long deadline = System.nanoTime() + 60_000_000_000L;
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelled");
            if (System.nanoTime() > deadline) throw new java.io.InterruptedIOException("Body read timed out");
            int n = in.read(buf);
            if (n < 0) break;
            if (n == 0) continue;
            if ((long) out.size() + n > maxBytes) throw new IOException("over " + maxBytes + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
